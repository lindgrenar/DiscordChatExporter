package io.github.dce.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** The token is kept in app-private storage only (backups are disabled in the manifest). */
object TokenStore {
    private const val PREFS = "auth"
    private const val KEY = "token"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, token: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (token == null) remove(KEY) else putString(KEY, token.trim().removeSurrounding("\""))
        }.apply()
    }
}

/** Remembered choices. Exporting and deleting each keep their own pace. */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_EXPORT_PAGE_DELAY = "export_page_delay"
    private const val KEY_DELETION_INTERVAL = "deletion_interval"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pause between pages of 100 messages while exporting; 0 is as fast as Discord allows. */
    fun exportPageDelay(context: Context): Long = prefs(context).getLong(KEY_EXPORT_PAGE_DELAY, 0L)

    fun setExportPageDelay(context: Context, millis: Long) =
        prefs(context).edit().putLong(KEY_EXPORT_PAGE_DELAY, millis).apply()

    /** Pause between deleted messages. */
    fun deletionInterval(context: Context): Long = prefs(context).getLong(KEY_DELETION_INTERVAL, 2000L)

    fun setDeletionInterval(context: Context, millis: Long) =
        prefs(context).edit().putLong(KEY_DELETION_INTERVAL, millis).apply()
}

/** A value shared between a background service and the UI; listeners are called on the main thread. */
open class ObservableState<T>(initial: T) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(T) -> Unit>()

    @Volatile
    var current = initial
        private set

    fun update(snapshot: T) {
        current = snapshot
        mainHandler.post { listeners.forEach { it(snapshot) } }
    }

    fun observe(listener: (T) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun unobserve(listener: (T) -> Unit) {
        listeners -= listener
    }
}

/** Export progress shared between [ExportService] and the UI. */
object ExportState : ObservableState<ExportState.Snapshot>(ExportState.Snapshot()) {
    enum class Phase { Idle, Running, Completed, Failed, Cancelled }

    data class Snapshot(
        val phase: Phase = Phase.Idle,
        val channelName: String = "",
        val fraction: Double = 0.0,
        val messages: Long = 0,
        val assets: Int = 0,
        val error: String? = null,
        /** The archive entry, once the export has completed. */
        val entryId: String? = null,
    )
}

/** Progress of deleting messages, shared between [DeletionService] and the UI. */
object DeletionState : ObservableState<DeletionState.Snapshot>(DeletionState.Snapshot()) {
    enum class Phase { Idle, Running, Completed, Failed, Stopped }

    data class Snapshot(
        val phase: Phase = Phase.Idle,
        val entryId: String? = null,
        val channelName: String = "",
        val intervalMillis: Long = 0,
        /** Own messages in the export, for an estimate of what's left. */
        val expected: Long = 0,
        val scanned: Long = 0,
        val deleted: Long = 0,
        val skipped: Long = 0,
        val fraction: Double = 0.0,
        val error: String? = null,
    ) {
        val remainingEstimate get() = (expected - deleted - skipped).coerceAtLeast(0)
    }
}
