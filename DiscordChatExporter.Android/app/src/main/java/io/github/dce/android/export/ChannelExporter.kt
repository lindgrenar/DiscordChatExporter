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

enum class ExportFormat {
    /** One self-contained .html file with every attachment embedded in it. */
    SingleHtml,

    /** A .zip with the .html file plus a folder of attachments it links to. */
    ZipArchive,
}

enum class ExportTheme { Dark, Light }

data class ExportProgress(val fraction: Double, val messages: Long, val assets: Int)

/** Loads the bundled stylesheet and scripts (theme-dark.css, theme-light.css, head.html). */
fun interface TemplateSource {
    fun read(name: String): String
}

/** Port of ChannelExporter, specialized for exporting a whole DM channel to HTML. */
class ChannelExporter(private val templates: TemplateSource) {
    fun export(
        client: DiscordClient,
        channel: Channel,
        me: User?,
        output: OutputStream,
        format: ExportFormat,
        theme: ExportTheme,
        baseName: String,
        tempDir: File,
        cancellation: CancellationSignal,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val css = templates.read(if (theme == ExportTheme.Dark) "theme-dark.css" else "theme-light.css")
        val head = templates.read("head.html")
        val downloader = AssetDownloader(File(tempDir, "assets"), cancellation)

        fun run(stream: OutputStream, assets: AssetSink, trailer: String) {
            val context = ExportContext(client, assets)
            val renderer = HtmlRenderer(stream, context, assets, css, head)
            renderer.writePreamble(channel, me)
            var lastFraction = 0.0
            client.forEachMessage(
                channel.id,
                onProgress = { lastFraction = it },
                onMessage = {
                    renderer.writeMessage(it)
                    onProgress(ExportProgress(lastFraction, renderer.messagesWritten, assets.count))
                },
            )
            renderer.writePostamble(trailer)
            onProgress(ExportProgress(1.0, renderer.messagesWritten, assets.count))
        }

        when (format) {
            ExportFormat.SingleHtml -> {
                val stream = BufferedOutputStream(output, 64 * 1024)
                run(stream, EmbeddedAssetSink(downloader), EmbeddedAssetSink.RESOLVER_SCRIPT)
                stream.flush()
            }

            ExportFormat.ZipArchive -> {
                // Assets go into the archive as they're downloaded; the HTML is staged in a temp
                // file and added last, because ZIP entries can't be written concurrently.
                val zip = ZipOutputStream(BufferedOutputStream(output, 64 * 1024))
                val htmlFile = File(tempDir, "export.html")
                htmlFile.outputStream().buffered().use { html ->
                    run(html, ZipAssetSink(downloader, zip, "$baseName.html_Files"), "")
                }
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry("$baseName.html"))
                htmlFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.finish()
                zip.flush()
                htmlFile.delete()
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
