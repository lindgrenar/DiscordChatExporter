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
import io.github.dce.android.archive.ArchiveEntry
import io.github.dce.android.archive.Sharing
import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.export.ChannelExporter
import io.github.dce.android.export.ExportFormat
import io.github.dce.android.export.ExportOptions
import io.github.dce.android.export.ExportTheme
import io.github.dce.android.export.TemplateSource
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an export in the foreground so that long chats keep exporting while the app is in the background.
 * Exports are saved to the on-device [Archive]. Only one export runs at a time.
 */
class ExportService : Service() {
    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_BASE_NAME = "base_name"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_THEME = "theme"
        private const val EXTRA_DOWNLOAD = "download"
        private const val EXTRA_SINGLE_FILE = "single_file"
        private const val NOTIFICATION_CHANNEL = "export"
        private const val PROGRESS_NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2

        fun start(
            context: Context,
            channelId: String,
            channelName: String,
            baseName: String,
            options: ExportOptions,
        ) {
            val intent = Intent(context, ExportService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
                .putExtra(EXTRA_BASE_NAME, baseName)
                .putExtra(EXTRA_FORMAT, options.format.name)
                .putExtra(EXTRA_THEME, options.theme.name)
                .putExtra(EXTRA_DOWNLOAD, options.downloadAssets)
                .putExtra(EXTRA_SINGLE_FILE, options.singleFile)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
        val token = TokenStore.get(this)
        if (channelId == null || token == null || worker != null) {
            // Satisfy the startForegroundService() contract even when there's nothing to do
            startInForeground(buildProgressNotification("Preparing…", 0.0))
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: channelId
        val baseName = intent.getStringExtra(EXTRA_BASE_NAME) ?: channelId
        val options = ExportOptions(
            format = ExportFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT) ?: ExportFormat.Html.name),
            theme = ExportTheme.valueOf(intent.getStringExtra(EXTRA_THEME) ?: ExportTheme.Dark.name),
            downloadAssets = intent.getBooleanExtra(EXTRA_DOWNLOAD, true),
            singleFile = intent.getBooleanExtra(EXTRA_SINGLE_FILE, true),
        )

        cancelled.set(false)
        ExportState.update(ExportState.Snapshot(ExportState.Phase.Running, channelName))
        startInForeground(buildProgressNotification(channelName, 0.0))

        worker = Thread({ runExport(token, channelId, channelName, baseName, options) }, "export").apply { start() }
        return START_NOT_STICKY
    }

    private fun runExport(
        token: String,
        channelId: String,
        channelName: String,
        baseName: String,
        options: ExportOptions,
    ) {
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dce:export")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        val tempDir = File(cacheDir, "export-${System.currentTimeMillis()}")
        val signal = CancellationSignal { cancelled.get() }
        val archive = Archive.of(this)
        archive.removeIncomplete()
        val output = archive.begin("$baseName.${options.outputExtension}")

        val result = try {
            val client = DiscordClient(token, signal)
            val me = runCatching { client.getCurrentUser() }.getOrNull()
            val channel = client.getChannel(channelId)
            val templates = TemplateSource { name -> assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } }

            var lastUpdateAt = 0L
            val exported = output.outputStream().use {
                ChannelExporter(templates).export(client, channel, me, it, options, baseName, tempDir, signal) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdateAt >= 200 || progress.fraction >= 1.0) {
                        lastUpdateAt = now
                        ExportState.update(
                            ExportState.current.copy(fraction = progress.fraction, messages = progress.messages, assets = progress.assets),
                        )
                        updateProgressNotification(channelName, progress.fraction)
                    }
                }
            }
            val entry = ArchiveEntry(
                id = output.parentFile!!.name,
                channelId = channel.id,
                channelName = channelName,
                fileName = output.name,
                mimeType = options.outputMimeType,
                createdAt = Instant.now(),
                messages = exported.messages,
                assets = exported.assets,
                ownerId = me?.id,
                ownMessages = exported.ownMessages,
                lastMessageId = exported.lastMessageId,
            )
            archive.commit(entry)
            ExportState.current.copy(phase = ExportState.Phase.Completed, fraction = 1.0, entryId = entry.id)
        } catch (e: CancelledException) {
            archive.abort(output)
            ExportState.current.copy(phase = ExportState.Phase.Cancelled)
        } catch (e: Throwable) {
            archive.abort(output)
            ExportState.current.copy(phase = ExportState.Phase.Failed, error = e.message ?: e.javaClass.simpleName)
        } finally {
            tempDir.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
        }

        ExportState.update(result)
        showResultNotification(result)
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgressNotification(title: String, fraction: Double): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val snapshot = ExportState.current
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Exporting $title")
            .setContentText("${snapshot.messages} messages · ${snapshot.assets} files")
            .setProgress(1000, (fraction * 1000).toInt(), fraction <= 0.0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent).build())
            .build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(title: String, fraction: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < 750) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            .notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(title, fraction))
    }

    private fun showResultNotification(snapshot: ExportState.Snapshot) {
        val text = when (snapshot.phase) {
            ExportState.Phase.Completed -> "Exported ${snapshot.messages} messages and ${snapshot.assets} files"
            ExportState.Phase.Cancelled -> "Export cancelled"
            else -> "Export failed: ${snapshot.error}"
        }
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.channelName)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())

        // Share straight from the notification, e.g. to back the export up to Drive or Proton Drive
        val entry = snapshot.entryId?.let { Archive.of(this).get(it) }
        if (entry != null) {
            fun action(requestCode: Int, label: String, intent: Intent) = Notification.Action.Builder(
                null, label,
                PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
            Sharing.quickShareIntent(this, entry)?.let { quick ->
                builder.addAction(action(3, "Send to ${Sharing.targetLabel(this)}", quick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            }
            builder.addAction(action(4, "Share…", Sharing.chooser(this, listOf(entry)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        }
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }
}
