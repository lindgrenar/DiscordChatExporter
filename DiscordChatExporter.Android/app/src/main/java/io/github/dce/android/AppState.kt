package io.github.dce.android

import android.content.Context
import android.net.Uri
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

/** Export progress shared between [ExportService] and the UI. */
object ExportState {
    enum class Phase { Idle, Running, Completed, Failed, Cancelled }

    data class Snapshot(
        val phase: Phase = Phase.Idle,
        val channelName: String = "",
        val fraction: Double = 0.0,
        val messages: Long = 0,
        val assets: Int = 0,
        val error: String? = null,
        val outputUri: Uri? = null,
        val outputMimeType: String? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    @Volatile
    var current = Snapshot()
        private set

    fun update(snapshot: Snapshot) {
        current = snapshot
        mainHandler.post { listeners.forEach { it(snapshot) } }
    }

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun unobserve(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
