package io.github.dce.android.archive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** The app's export archive, kept in private storage. */
object Archive {
    @Volatile
    private var store: ArchiveStore? = null

    fun of(context: Context): ArchiveStore =
        store ?: synchronized(this) {
            store ?: ArchiveStore(File(context.applicationContext.filesDir, "archive")).also { store = it }
        }

    /** A content URI other apps (Drive, Proton Drive, mail…) can read the export through, once granted. */
    fun uri(context: Context, entry: ArchiveEntry): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(ArchiveProvider.authority(context))
            .appendPath(entry.id)
            .appendPath(entry.fileName)
            .build()
}

/**
 * Serves archived exports read-only to apps they're shared with. A minimal FileProvider, without the
 * androidx dependency. Access is only possible through per-URI grants: the provider isn't exported.
 */
class ArchiveProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = "${context.packageName}.archive"

        private val DEFAULT_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return Archive.of(context ?: return null).resolve(segments[0], segments[1])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Archived exports are read-only.")
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection?.filter { it in DEFAULT_COLUMNS }?.toTypedArray() ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns, 1)
        if (file != null) {
            cursor.addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
        return cursor
    }

    override fun getType(uri: Uri): String =
        when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "html" -> "text/html"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
}
