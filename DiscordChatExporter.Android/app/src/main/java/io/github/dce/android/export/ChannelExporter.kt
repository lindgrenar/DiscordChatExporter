package io.github.dce.android.export

import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.Channel
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.User
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String) {
    Html("html", "text/html"),
    Json("json", "application/json"),
    Xml("xml", "text/xml"),
}

enum class ExportTheme { Dark, Light }

data class ExportOptions(
    val format: ExportFormat,
    val theme: ExportTheme = ExportTheme.Dark,
    /** Download attachments, avatars, emoji and embed media; otherwise they stay as links to Discord. */
    val downloadAssets: Boolean = true,
    /** HTML only: embed downloaded assets in the .html file itself instead of packaging a ZIP. */
    val singleFile: Boolean = true,
) {
    /** Downloaded assets go into a ZIP next to the main file, except for single-file HTML. */
    val isZip get() = downloadAssets && !(format == ExportFormat.Html && singleFile)
    val outputExtension get() = if (isZip) "zip" else format.extension
    val outputMimeType get() = if (isZip) "application/zip" else format.mimeType
}

data class ExportProgress(val fraction: Double, val messages: Long, val assets: Int)

data class ExportResult(
    val messages: Long,
    val assets: Int,
    /** Messages sent by the exporting user. */
    val ownMessages: Long,
    val lastMessageId: String?,
)

/** Loads the bundled stylesheet and scripts (theme-dark.css, theme-light.css, head.html). */
fun interface TemplateSource {
    fun read(name: String): String
}

/** Leaves every asset as a link to its original location. */
private object LinkedAssetSink : AssetSink {
    override fun resolve(url: String) = url
    override val count = 0
}

/** Port of ChannelExporter, specialized for exporting a whole DM channel. */
class ChannelExporter(private val templates: TemplateSource) {
    fun export(
        client: DiscordClient,
        channel: Channel,
        me: User?,
        output: OutputStream,
        options: ExportOptions,
        baseName: String,
        tempDir: File,
        cancellation: CancellationSignal,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val downloader = AssetDownloader(File(tempDir, "assets"), cancellation)

        fun createWriter(stream: OutputStream, context: ExportContext, assets: AssetSink, trailer: String): MessageWriter =
            when (options.format) {
                ExportFormat.Html -> HtmlRenderer(
                    stream, context, assets,
                    css = templates.read(if (options.theme == ExportTheme.Dark) "theme-dark.css" else "theme-light.css"),
                    headHtml = templates.read("head.html"),
                    trailer = trailer,
                )
                ExportFormat.Json -> DataWriter(stream, isXml = false, context, assets)
                ExportFormat.Xml -> DataWriter(stream, isXml = true, context, assets)
            }

        fun run(stream: OutputStream, assets: AssetSink, trailer: String = ""): ExportResult {
            val writer = createWriter(stream, ExportContext(client, assets), assets, trailer)
            writer.writePreamble(channel, me)
            var lastFraction = 0.0
            var ownMessages = 0L
            var lastMessageId: String? = null
            client.forEachMessage(
                channel.id,
                onProgress = { lastFraction = it },
                onMessage = {
                    writer.writeMessage(it)
                    lastMessageId = it.id
                    if (it.author.id == me?.id) ownMessages++
                    onProgress(ExportProgress(lastFraction, writer.messagesWritten, assets.count))
                },
            )
            writer.writePostamble()
            onProgress(ExportProgress(1.0, writer.messagesWritten, assets.count))
            return ExportResult(writer.messagesWritten, assets.count, ownMessages, lastMessageId)
        }

        return when {
            !options.downloadAssets -> {
                val stream = BufferedOutputStream(output, 64 * 1024)
                run(stream, LinkedAssetSink).also { stream.flush() }
            }

            !options.isZip -> {
                val stream = BufferedOutputStream(output, 64 * 1024)
                run(stream, EmbeddedAssetSink(downloader), EmbeddedAssetSink.RESOLVER_SCRIPT).also { stream.flush() }
            }

            else -> {
                // Assets go into the archive as they're downloaded; the main file is staged in a temp
                // file and added last, because ZIP entries can't be written concurrently.
                val fileName = "$baseName.${options.format.extension}"
                val zip = ZipOutputStream(BufferedOutputStream(output, 64 * 1024))
                val staged = File(tempDir, "export.tmp")
                tempDir.mkdirs()
                val result = staged.outputStream().buffered().use { stream ->
                    run(stream, ZipAssetSink(downloader, zip, "${fileName}_Files"))
                }
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(fileName))
                staged.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.finish()
                zip.flush()
                staged.delete()
                result
            }
        }
    }

    companion object {
        /** Mirrors the desktop app's default file name template: "%G - %c [%C]". */
        fun defaultBaseName(channel: Channel): String =
            "Direct Messages - ${channel.name} [${channel.id}]"
                .map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }
                .joinToString("")
                .take(120)
    }
}
