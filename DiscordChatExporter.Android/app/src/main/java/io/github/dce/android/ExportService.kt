package io.github.dce.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.export.ChannelExporter
import io.github.dce.android.export.ExportFormat
import io.github.dce.android.export.ExportOptions
import io.github.dce.android.export.ExportTheme
import io.github.dce.android.export.TemplateSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an export in the foreground so that long chats keep exporting while the app is in the background.
 * Only one export runs at a time.
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
            output: Uri,
            options: ExportOptions,
        ) {
            val intent = Intent(context, ExportService::class.java)
                .setAction(ACTION_START)
                .setData(output)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

        val output = intent?.data
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
        val token = TokenStore.get(this)
        if (output == null || channelId == null || token == null || worker != null) {
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

        worker = Thread({ runExport(token, channelId, channelName, baseName, output, options) }, "export").apply { start() }
        return START_NOT_STICKY
    }

    private fun runExport(
        token: String,
        channelId: String,
        channelName: String,
        baseName: String,
        output: Uri,
        options: ExportOptions,
    ) {
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dce:export")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        val tempDir = File(cacheDir, "export-${System.currentTimeMillis()}")
        val mimeType = options.outputMimeType
        val signal = CancellationSignal { cancelled.get() }

        val result = try {
            val client = DiscordClient(token, signal)
            val me = runCatching { client.getCurrentUser() }.getOrNull()
            val channel = client.getChannel(channelId)
            val templates = TemplateSource { name -> assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } }

            // Some storage providers don't support truncation ("wt"); the document is new anyway
            val stream = runCatching { contentResolver.openOutputStream(output, "wt") }.getOrNull()
                ?: contentResolver.openOutputStream(output, "w")
                ?: error("Couldn't open the output file.")
            var lastUpdateAt = 0L
            stream.use {
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
            ExportState.current.copy(phase = ExportState.Phase.Completed, fraction = 1.0, outputUri = output, outputMimeType = mimeType)
        } catch (e: CancelledException) {
            deletePartialOutput(output)
            ExportState.current.copy(phase = ExportState.Phase.Cancelled)
        } catch (e: Throwable) {
            deletePartialOutput(output)
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

    private fun deletePartialOutput(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
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
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.channelName)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }
}
