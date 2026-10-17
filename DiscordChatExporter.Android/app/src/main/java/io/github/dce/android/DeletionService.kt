package io.github.dce.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.dce.android.archive.Archive
import io.github.dce.android.delete.MessageDeleter
import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.DiscordException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Slowly deletes the user's own messages in an archived chat, at a user-chosen pace, in the foreground so it
 * keeps going for hours while the app is in the background. Only one deletion runs at a time.
 */
class DeletionService : Service() {
    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_INTERVAL = "interval"
        private const val NOTIFICATION_CHANNEL = "deletion"
        private const val PROGRESS_NOTIFICATION_ID = 11
        private const val RESULT_NOTIFICATION_ID = 12

        fun start(context: Context, entryId: String, intervalMillis: Long) {
            val intent = Intent(context, DeletionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_INTERVAL, intervalMillis)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DeletionService::class.java).setAction(ACTION_STOP))
        }

        /** "1 every 2 s", "1 every 1.5 s" */
        fun describeInterval(intervalMillis: Long): String {
            val seconds = intervalMillis / 1000.0
            val text = if (seconds == Math.floor(seconds)) seconds.toLong().toString() else "%.1f".format(seconds)
            return "1 every $text s"
        }

        /** "3 h 20 min", "12 min", "under a minute" */
        fun describeDuration(millis: Long): String {
            val minutes = millis / 60_000
            return when {
                minutes < 1 -> "under a minute"
                minutes < 60 -> "$minutes min"
                minutes % 60 == 0L -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }
    }

    private val stopped = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (intent?.action == ACTION_STOP) {
            stopped.set(true)
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        val entry = intent?.getStringExtra(EXTRA_ENTRY_ID)?.let { Archive.of(this).get(it) }
        val token = TokenStore.get(this)
        if (entry == null || token == null || worker != null) {
            // Satisfy the startForegroundService() contract even when there's nothing to do
            startInForeground(buildProgressNotification())
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        val interval = intent.getLongExtra(EXTRA_INTERVAL, 2000L).coerceAtLeast(MessageDeleter.MIN_INTERVAL_MILLIS)
        stopped.set(false)
        DeletionState.update(
            DeletionState.Snapshot(
                phase = DeletionState.Phase.Running,
                entryId = entry.id,
                channelName = entry.channelName,
                intervalMillis = interval,
                expected = (entry.ownMessages - entry.deletedMessages).coerceAtLeast(0),
            ),
        )
        if (!startInForeground(buildProgressNotification())) {
            // E.g. a restart by the system while the app is in the background, which Android 12+ doesn't allow
            DeletionState.update(DeletionState.current.copy(phase = DeletionState.Phase.Stopped))
            stopSelf()
            return START_NOT_STICKY
        }

        worker = Thread({ runDeletion(token, entry.id, interval) }, "deletion").apply { start() }
        // If the system kills the app mid-way, start over with the same request: already deleted messages are gone,
        // so the next run continues where this one stopped
        return START_REDELIVER_INTENT
    }

    private fun runDeletion(token: String, entryId: String, interval: Long) {
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dce:deletion")
            .apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        val signal = CancellationSignal { stopped.get() }
        val archive = Archive.of(this)
        var recorded = 0L

        // Persist progress as it goes, so the archive shows how far it got even if the app is killed
        fun record(deleted: Long, completed: Boolean = false) {
            val delta = deleted - recorded
            recorded = deleted
            archive.update(entryId) {
                it.copy(
                    deletedMessages = it.deletedMessages + delta,
                    deletionCompletedAt = if (completed) Instant.now() else it.deletionCompletedAt,
                )
            }
        }

        val result = try {
            val entry = archive.get(entryId) ?: error("The export is no longer in the archive.")
            // Never delete without a bound: only what the export covers
            val upTo = entry.lastMessageId ?: error("This export has no messages.")
            val client = DiscordClient(token, signal)
            val me = client.getCurrentUser()
            if (entry.ownerId != null && entry.ownerId != me.id) {
                throw DiscordException("This export was made with a different account than the one signed in now.", isFatal = true)
            }

            var lastSavedAt = SystemClock.elapsedRealtime()
            val progress = MessageDeleter(client, signal).run(entry.channelId, me.id, upTo, interval) { p ->
                // Re-arm the wake lock as long as there's progress, instead of holding it indefinitely
                wakeLock.acquire(10 * 60 * 1000L)
                DeletionState.update(
                    DeletionState.current.copy(scanned = p.scanned, deleted = p.deleted, skipped = p.skipped, fraction = p.fraction),
                )
                updateProgressNotification()
                val now = SystemClock.elapsedRealtime()
                if (now - lastSavedAt >= 15_000) {
                    lastSavedAt = now
                    record(p.deleted)
                }
            }
            record(progress.deleted, completed = true)
            DeletionState.current.copy(phase = DeletionState.Phase.Completed, fraction = 1.0)
        } catch (e: CancelledException) {
            record(DeletionState.current.deleted)
            DeletionState.current.copy(phase = DeletionState.Phase.Stopped)
        } catch (e: Throwable) {
            record(DeletionState.current.deleted)
            DeletionState.current.copy(phase = DeletionState.Phase.Failed, error = e.message ?: e.javaClass.simpleName)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

        DeletionState.update(result)
        showResultNotification(result)
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, "Message deletion", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun openArchiveIntent() = PendingIntent.getActivity(
        this, 10,
        Intent(this, ArchiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgressNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, DeletionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val state = DeletionState.current
        val eta = state.remainingEstimate.takeIf { it > 0 }
            ?.let { " · ~${describeDuration(it * state.intervalMillis)} left" }.orEmpty()
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Deleting your messages in ${state.channelName}")
            .setContentText("${state.deleted} deleted · ${describeInterval(state.intervalMillis)}$eta")
            .setProgress(1000, (state.fraction * 1000).toInt(), state.scanned == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openArchiveIntent())
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun startInForeground(notification: Notification): Boolean = runCatching {
        when {
            // Special use has no daily time limit, unlike data sync on Android 15+
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }.isSuccess

    private fun updateProgressNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < 750) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification())
    }

    private fun showResultNotification(snapshot: DeletionState.Snapshot) {
        val skipped = if (snapshot.skipped > 0) " (${snapshot.skipped} couldn't be deleted)" else ""
        val text = when (snapshot.phase) {
            DeletionState.Phase.Completed -> "Done: deleted ${snapshot.deleted} messages$skipped"
            DeletionState.Phase.Stopped -> "Stopped after ${snapshot.deleted} messages. Resume from the archive."
            else -> "Stopped: ${snapshot.error}"
        }
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.channelName)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openArchiveIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopped.set(true)
        super.onDestroy()
    }
}
