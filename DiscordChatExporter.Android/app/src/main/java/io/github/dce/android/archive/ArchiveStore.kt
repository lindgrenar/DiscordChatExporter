package io.github.dce.android.archive

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** An export kept on the device. */
data class ArchiveEntry(
    val id: String,
    val channelId: String,
    val channelName: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: Instant,
    val messages: Long,
    val assets: Int,
    /** The account that made the export, and how many of the exported messages are theirs. */
    val ownerId: String?,
    val ownMessages: Long,
    /** The newest exported message; deletion never goes past it. */
    val lastMessageId: String?,
    val deletedMessages: Long = 0,
    val deletionCompletedAt: Instant? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("channelId", channelId)
        .put("channelName", channelName)
        .put("fileName", fileName)
        .put("mimeType", mimeType)
        .put("createdAt", createdAt.toString())
        .put("messages", messages)
        .put("assets", assets)
        .put("ownerId", ownerId ?: JSONObject.NULL)
        .put("ownMessages", ownMessages)
        .put("lastMessageId", lastMessageId ?: JSONObject.NULL)
        .put("deletedMessages", deletedMessages)
        .put("deletionCompletedAt", deletionCompletedAt?.toString() ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.strOrNull(name: String) = if (isNull(name)) null else optString(name)

        fun parse(json: JSONObject) = ArchiveEntry(
            id = json.getString("id"),
            channelId = json.getString("channelId"),
            channelName = json.getString("channelName"),
            fileName = json.getString("fileName"),
            mimeType = json.getString("mimeType"),
            createdAt = Instant.parse(json.getString("createdAt")),
            messages = json.optLong("messages"),
            assets = json.optInt("assets"),
            ownerId = json.strOrNull("ownerId"),
            ownMessages = json.optLong("ownMessages"),
            lastMessageId = json.strOrNull("lastMessageId"),
            deletedMessages = json.optLong("deletedMessages"),
            deletionCompletedAt = json.strOrNull("deletionCompletedAt")?.let(Instant::parse),
        )
    }
}

/**
 * Exports live in `<root>/<id>/<fileName>`, next to a `meta.json` describing them.
 * A folder without `meta.json` is an export that is still being written (or was interrupted).
 */
class ArchiveStore(val root: File) {
    private companion object {
        const val META = "meta.json"
    }

    /** Reserves a folder for a new export; [commit] it once the file is complete. */
    fun begin(fileName: String, now: Instant = Instant.now()): File = synchronized(this) {
        root.mkdirs()
        var id = now.toEpochMilli().toString()
        while (File(root, id).exists()) id = (id.toLong() + 1).toString()
        File(File(root, id).apply { mkdirs() }, fileName)
    }

    fun commit(entry: ArchiveEntry) = save(entry)

    fun abort(file: File) {
        file.parentFile?.canonicalFile?.takeIf { it.parentFile == root.canonicalFile }?.deleteRecursively()
    }

    /** Removes the leftovers of exports that never finished. */
    fun removeIncomplete() = synchronized(this) {
        root.listFiles()?.filter { it.isDirectory && !File(it, META).exists() }?.forEach { it.deleteRecursively() }
    }

    fun list(): List<ArchiveEntry> = synchronized(this) {
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { ArchiveEntry.parse(JSONObject(File(dir, META).readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): ArchiveEntry? = synchronized(this) {
        runCatching { ArchiveEntry.parse(JSONObject(File(File(root, id), META).readText())) }.getOrNull()
    }

    fun save(entry: ArchiveEntry) = synchronized(this) {
        val dir = File(root, entry.id)
        val temp = File(dir, "$META.tmp")
        temp.writeText(entry.toJson().toString(2))
        if (!temp.renameTo(File(dir, META))) error("Couldn't save the archive entry.")
    }

    /** Applies [change] to the stored entry, if it still exists. */
    fun update(id: String, change: (ArchiveEntry) -> ArchiveEntry): ArchiveEntry? = synchronized(this) {
        get(id)?.let(change)?.also(::save)
    }

    fun delete(id: String) = synchronized(this) {
        resolveFolder(id)?.deleteRecursively()
    }

    fun file(entry: ArchiveEntry) = File(File(root, entry.id), entry.fileName)

    /** The file for a content URI path, or null if the path doesn't point at an archived export. */
    fun resolve(id: String, fileName: String): File? {
        val folder = resolveFolder(id) ?: return null
        val file = File(folder, fileName).canonicalFile
        return file.takeIf { it.parentFile == folder && it.name != META && it.isFile }
    }

    private fun resolveFolder(id: String): File? =
        File(root, id).canonicalFile.takeIf { it.parentFile == root.canonicalFile && it.isDirectory }

    fun size(entry: ArchiveEntry) = file(entry).length()
}
