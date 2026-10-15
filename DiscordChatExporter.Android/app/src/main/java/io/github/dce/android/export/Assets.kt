package io.github.dce.android.export

import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.Http
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadedAsset(val file: File, val fileName: String, val mimeType: String)

/** Port of ExportAssetDownloader: downloads assets to a temp directory using the same file naming scheme. */
class AssetDownloader(private val tempDir: File, private val cancellation: CancellationSignal) {
    fun download(url: String): DownloadedAsset? {
        val fileName = fileNameFromUrl(url)
        val file = File(tempDir, fileName)
        tempDir.mkdirs()

        return try {
            val response = Http.get(url, cancellation = cancellation, maxAttempts = 3)
            try {
                if (!response.isSuccess) return null
                response.stream().use { input -> file.outputStream().use { input.copyTo(it) } }
                val contentType = response.headers["content-type"]?.substringBefore(';')?.trim()
                DownloadedAsset(file, fileName, resolveMimeType(contentType, fileName))
            } finally {
                response.close()
            }
        } catch (e: CancelledException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            // Assets are best-effort: a failed download falls back to the remote URL
            file.delete()
            null
        }
    }

    companion object {
        private val signatureParams = setOf("ex", "is", "hm")

        /** Removes the expiring signature parameters from Discord CDN URLs so that the same file maps to the same name. */
        fun normalizeUrl(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            val host = uri.host?.lowercase() ?: return url
            if (host != "cdn.discordapp.com" && host != "media.discordapp.net") return url
            val query = uri.rawQuery?.split('&')
                ?.filter { it.isNotEmpty() && it.substringBefore('=') !in signatureParams }
                ?.joinToString("&")
            val base = url.substringBefore('?')
            return if (query.isNullOrEmpty()) base else "$base?$query"
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun escapeFileName(name: String): String =
            name.map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }.joinToString("")

        fun fileNameFromUrl(url: String): String {
            // 16 hex chars = 64 bits of the hash
            val hash = sha256Hex(normalizeUrl(url)).take(16)
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            val fileName = java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
            if (fileName.isBlank()) return hash

            var nameWithoutExtension = fileName.substringBeforeLast('.', fileName)
            var extension = if (fileName.contains('.')) "." + fileName.substringAfterLast('.') else ""

            // Probably not an extension, just a dot in a long file name
            if (extension.length > 41) {
                nameWithoutExtension = fileName
                extension = ""
            }

            return escapeFileName(nameWithoutExtension.take(42) + "-" + hash + extension)
        }

        private val mimeTypes = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml",
            "mp4" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac",
            "m4a" to "audio/mp4", "json" to "application/json", "pdf" to "application/pdf",
            "txt" to "text/plain",
        )

        fun resolveMimeType(contentType: String?, fileName: String): String {
            if (!contentType.isNullOrBlank() && contentType != "application/octet-stream" && '/' in contentType) {
                return contentType
            }
            return mimeTypes[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        }
    }
}

/** Decides how downloaded assets end up in the export, and what the HTML references them by. */
interface AssetSink {
    /** Returns the reference to put in the HTML for [url], or [url] itself if the asset couldn't be downloaded. */
    fun resolve(url: String): String

    /** Called between chunks of HTML, so that assets resolved by the last chunk can be written out. */
    fun flush(out: OutputStream) {}

    val count: Int
}

/** Writes pending bytes without closing the underlying stream (base64 encoders close on finish). */
private class NonClosingStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

/**
 * Embeds every asset into the HTML file itself.
 * Each unique asset is written once, as a base64 data block placed right after the HTML that first uses it.
 * Elements reference it via a "dce-asset:N" URL, which a small script swaps for a blob URL as it scrolls into view.
 */
class EmbeddedAssetSink(private val downloader: AssetDownloader) : AssetSink {
    private val refsByUrl = HashMap<String, String>()
    private val pending = ArrayList<Pair<Int, DownloadedAsset>>()
    private var nextId = 0

    override val count get() = nextId

    override fun resolve(url: String): String {
        val key = AssetDownloader.normalizeUrl(url)
        refsByUrl[key]?.let { return it }

        val asset = downloader.download(url)
        val ref = if (asset != null) {
            val id = nextId++
            pending += id to asset
            "dce-asset:$id"
        } else {
            url
        }
        refsByUrl[key] = ref
        return ref
    }

    override fun flush(out: OutputStream) {
        for ((id, asset) in pending) {
            out.write("""<script type="application/octet-stream" id="dce-asset-$id" data-mime="${asset.mimeType.html()}" data-name="${asset.fileName.html()}">""".toByteArray())
            Base64.getEncoder().wrap(NonClosingStream(out)).use { encoder ->
                asset.file.inputStream().use { it.copyTo(encoder) }
            }
            out.write("</script>\n".toByteArray())
            asset.file.delete()
        }
        pending.clear()
    }

    companion object {
        // Swaps "dce-asset:N" references for blob URLs lazily, so that large exports stay responsive.
        val RESOLVER_SCRIPT = """
<script>
(function () {
    var cache = {};
    function decode(id) {
        if (cache[id]) return cache[id];
        var el = document.getElementById('dce-asset-' + id);
        if (!el) return Promise.reject(new Error('missing asset ' + id));
        var mime = el.getAttribute('data-mime') || 'application/octet-stream';
        var b64 = el.textContent.trim();
        cache[id] = fetch('data:' + mime + ';base64,' + b64)
            .then(function (r) { return r.blob(); })
            .catch(function () {
                var bin = atob(b64), bytes = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                return new Blob([bytes], { type: mime });
            })
            .then(function (blob) { return URL.createObjectURL(blob); });
        return cache[id];
    }
    function resolveAttr(el, attr) {
        var value = el.getAttribute(attr);
        if (!value || value.indexOf('dce-asset:') !== 0) return;
        var id = value.substring(10);
        decode(id).then(function (url) {
            el.setAttribute(attr, url);
            if (el.tagName === 'SOURCE' && el.parentElement && el.parentElement.load) el.parentElement.load();
        });
    }
    function resolveElement(el) {
        resolveAttr(el, 'src');
        resolveAttr(el, 'href');
        el.querySelectorAll && el.querySelectorAll('source').forEach(function (s) { resolveAttr(s, 'src'); });
    }
    document.addEventListener('DOMContentLoaded', function () {
        var targets = [];
        document.querySelectorAll('[src^="dce-asset:"], [href^="dce-asset:"]').forEach(function (el) {
            targets.push(el.tagName === 'SOURCE' && el.parentElement ? el.parentElement : el);
        });
        if (!('IntersectionObserver' in window)) { targets.forEach(resolveElement); return; }
        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                observer.unobserve(entry.target);
                resolveElement(entry.target);
            });
        }, { rootMargin: '1500px 0px' });
        targets.forEach(function (el) { observer.observe(el); });
    });
})();
</script>
""".trimIndent()
    }
}

/** Writes each asset as a separate file inside a ZIP archive, next to the HTML file that references it relatively. */
class ZipAssetSink(
    private val downloader: AssetDownloader,
    private val zip: ZipOutputStream,
    private val assetsDirName: String,
) : AssetSink {
    private val refsByUrl = HashMap<String, String>()

    override var count = 0
        private set

    override fun resolve(url: String): String {
        val key = AssetDownloader.normalizeUrl(url)
        refsByUrl[key]?.let { return it }

        val asset = downloader.download(url)
        val ref = if (asset != null) {
            // Media is already compressed; don't waste time deflating it again
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry("$assetsDirName/${asset.fileName}"))
            asset.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            asset.file.delete()
            count++
            encodePath(assetsDirName) + "/" + encodePath(asset.fileName)
        } else {
            url
        }
        refsByUrl[key] = ref
        return ref
    }

    private fun encodePath(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
