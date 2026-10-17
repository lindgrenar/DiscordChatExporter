package io.github.dce.android

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import io.github.dce.android.archive.Archive
import io.github.dce.android.archive.ArchiveEntry
import io.github.dce.android.archive.Sharing

/** Open / share actions for archived exports, used by both the main screen and the archive. */
object ArchiveUi {
    fun open(activity: Activity, entry: ArchiveEntry) {
        val uri = Archive.uri(activity, entry)
        if (entry.mimeType == "text/html") {
            activity.startActivity(
                Intent(activity, ViewerActivity::class.java).setData(uri).putExtra(ViewerActivity.EXTRA_TITLE, entry.channelName),
            )
        } else {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { activity.startActivity(Intent.createChooser(intent, "Open with")) }
                .onFailure { toast(activity, "No app can open this file.") }
        }
    }

    fun share(activity: Activity, entries: List<ArchiveEntry>) {
        if (entries.isEmpty()) return
        activity.startActivity(Sharing.chooser(activity, entries))
    }

    /** Sends to the app last picked in the share sheet; falls back to the share sheet. */
    fun quickShare(activity: Activity, entry: ArchiveEntry) {
        val intent = Sharing.quickShareIntent(activity, entry)
        if (intent == null || runCatching { activity.startActivity(intent) }.isFailure) {
            Sharing.forgetTarget(activity)
            share(activity, listOf(entry))
        }
    }

    fun quickShareLabel(activity: Activity): String? = Sharing.targetLabel(activity)?.let { "Send to $it" }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
