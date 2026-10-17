package io.github.dce.android.archive

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Sends exports to other apps through the system share sheet: Google Drive, Proton Drive, Dropbox, mail…
 * Whichever app the user picks is remembered, so the next export goes there with one tap.
 */
object Sharing {
    private const val PREFS = "sharing"
    private const val KEY_TARGET = "target"

    private fun sendIntent(context: Context, entries: List<ArchiveEntry>): Intent {
        val uris = entries.map { Archive.uri(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(entries[0].mimeType)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .setType(entries.map { it.mimeType }.distinct().singleOrNull() ?: "*/*")
        }
        // ClipData carries the read grant to whichever app ends up handling the intent
        val clip = ClipData.newRawUri(entries[0].fileName, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return intent
            .putExtra(Intent.EXTRA_TITLE, entries.singleOrNull()?.fileName)
            .putExtra(Intent.EXTRA_SUBJECT, entries.singleOrNull()?.let { "Discord export: ${it.channelName}" } ?: "Discord exports")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = clip }
    }

    /** The share sheet for [entries]; the app picked from it becomes the quick-share target. */
    fun chooser(context: Context, entries: List<ArchiveEntry>): Intent {
        val callback = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ShareTargetReceiver::class.java),
            // Mutable so the system can add the chosen component; the intent is explicit
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (entries.size == 1) "Share export" else "Share ${entries.size} exports"
        return Intent.createChooser(sendIntent(context, entries), title, callback.intentSender)
    }

    /** Sends a single export straight to the remembered app, or null if there is none (anymore). */
    fun quickShareIntent(context: Context, entry: ArchiveEntry): Intent? {
        val target = target(context) ?: return null
        return sendIntent(context, listOf(entry)).setComponent(target)
    }

    /** Label of the remembered app, e.g. "Drive", if it's still installed and accepts files. */
    fun targetLabel(context: Context): String? {
        val target = target(context) ?: return null
        return runCatching {
            context.packageManager.getActivityInfo(target, 0).loadLabel(context.packageManager).toString()
        }.getOrNull()
    }

    private fun target(context: Context): ComponentName? {
        val target = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGET, null)
            ?.let(ComponentName::unflattenFromString)
            ?: return null
        val installed = runCatching { context.packageManager.getActivityInfo(target, 0) }.isSuccess
        return target.takeIf { installed }
    }

    internal fun setTarget(context: Context, target: ComponentName?) {
        // Never remember ourselves (e.g. if the app ever shows up in its own share sheet)
        if (target?.packageName == context.packageName) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (target == null) remove(KEY_TARGET) else putString(KEY_TARGET, target.flattenToString())
        }.apply()
    }

    fun forgetTarget(context: Context) = setTarget(context, null)
}

/** Told by the share sheet which app was picked. */
class ShareTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val chosen = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) ?: return
        Sharing.setTarget(context, chosen)
    }
}
