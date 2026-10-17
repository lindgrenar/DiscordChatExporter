package io.github.dce.android.export

import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.ImageCdn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Runs full exports against a local fake of the Discord API and CDN. */
class ExportEndToEndTest {
    private lateinit var server: FakeHttpServer
    private lateinit var base: String
    private val messageRequests = AtomicInteger()
    private val assetRequests = AtomicInteger()
    private val messages = mutableListOf<JSONObject>()

    // 1x1 transparent PNG
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    private val templates = TemplateSource { name -> File("src/main/assets/$name").readText() }

    private fun snowflake(instant: Instant) = ((instant.toEpochMilli() - 1420070400000L) shl 22).toString()

    private fun user(id: String, name: String, avatar: String? = null) =
        JSONObject().put("id", id).put("username", name).put("global_name", name.replaceFirstChar { it.uppercase() })
            .put("discriminator", "0").put("avatar", avatar ?: JSONObject.NULL)

    private val me = user("1", "me", avatar = "abc")
    private val alice = user("2", "alice")

    private fun json(value: Any, code: Int = 200) =
        FakeHttpServer.Response(code, value.toString().toByteArray(), "application/json")

    private fun handle(request: FakeHttpServer.Request): FakeHttpServer.Response {
        if (request.path.startsWith("/cdn/")) {
            assetRequests.incrementAndGet()
            return when {
                request.path.endsWith("missing.png") -> FakeHttpServer.Response(404, ByteArray(0), "text/plain")
                request.path.endsWith(".txt") -> FakeHttpServer.Response(200, "hello attachment".toByteArray(), "text/plain")
                else -> FakeHttpServer.Response(200, png, "image/png")
            }
        }

        if (request.header("Authorization") != "secret") return json(JSONObject(), 401)
        val path = request.path.removePrefix("/api/")
        return when (path) {
            "users/@me" -> json(me)
            // On-demand lookup for mentioned users that haven't appeared in the chat yet
            "users/2" -> json(alice)
            "channels/100" -> json(JSONObject().put("id", "100").put("type", 1).put("recipients", JSONArray().put(alice)))
            "channels/100/messages" -> {
                val q = request.query
                // The first page request is rate limited once, to exercise the retry path
                if (q["after"] != null && messageRequests.getAndIncrement() == 0) {
                    return FakeHttpServer.Response(429, "{}".toByteArray(), "application/json", mapOf("Retry-After" to "0"))
                }
                val limit = q["limit"]!!.toInt()
                val page = q["after"]?.let { after -> messages.filter { it.getString("id").toULong() > after.toULong() }.take(limit) }
                    ?: messages.takeLast(limit)
                json(JSONArray(page.reversed()))
            }
            else -> when {
                path.startsWith("channels/100/messages/") && "/reactions/" in path ->
                    json(if (request.query["after"] == "0") JSONArray().put(alice) else JSONArray())
                else -> json(JSONObject(), 404)
            }
        }
    }

    @Before
    fun setUp() {
        server = FakeHttpServer(::handle)
        server.start()
        base = "http://127.0.0.1:${server.port}"
        ImageCdn.baseUrl = "$base/cdn"

        // 250 messages -> 3 pages of 100
        val start = Instant.parse("2024-01-01T12:00:00Z")
        repeat(250) { i ->
            val timestamp = start.plusSeconds(i * 60L)
            val message = JSONObject()
                .put("id", snowflake(timestamp))
                .put("type", 0)
                .put("author", if (i % 2 == 0) me else alice)
                .put("timestamp", timestamp.toString())
                .put("content", "Message #$i with **bold** and <@2>")
            when (i) {
                0 -> message.put("attachments", JSONArray().put(
                    JSONObject().put("id", "9").put("filename", "photo.png").put("size", png.size)
                        .put("url", "$base/cdn/attachments/1/photo.png?ex=1&is=2&hm=3"),
                ))
                1 -> message.put("attachments", JSONArray().put(
                    JSONObject().put("id", "10").put("filename", "notes.txt").put("size", 16)
                        .put("url", "$base/cdn/attachments/1/notes.txt"),
                ))
                2 -> message.put("content", "<:wave:555> <:wave:555>")
                3 -> message.put("attachments", JSONArray().put(
                    JSONObject().put("id", "11").put("filename", "gone.png").put("size", 1)
                        .put("url", "$base/cdn/attachments/1/missing.png"),
                ))
                4 -> message.put("type", 19).put("referenced_message", messages[0])
                5 -> message.put("embeds", JSONArray().put(
                    JSONObject().put("type", "rich").put("title", "Embed title").put("description", "Embed *description*")
                        .put("color", 0x5865F2).put("url", "https://example.com"),
                ))
                6 -> message.put("type", 3).put("content", "").put("call", JSONObject().put("ended_timestamp", timestamp.plusSeconds(600).toString()))
                7 -> message.put("reactions", JSONArray().put(JSONObject().put("count", 2).put("emoji", JSONObject().put("name", "👍"))))
            }
            messages += message
        }
    }

    @After
    fun tearDown() {
        server.stop()
        ImageCdn.baseUrl = "https://cdn.discordapp.com"
    }

    private fun export(options: ExportOptions, cancellation: CancellationSignal = CancellationSignal { false }): ByteArray {
        val client = DiscordClient("secret", cancellation, "$base/api/")
        val channel = client.getChannel("100")
        val output = ByteArrayOutputStream()
        val tempDir = Files.createTempDirectory("dce-test").toFile()
        var lastProgress: ExportProgress? = null
        val result = ChannelExporter(templates).export(
            client, channel, client.getCurrentUser(), output, options,
            ChannelExporter.defaultBaseName(channel), tempDir, cancellation,
        ) { lastProgress = it }
        assertEquals(250L, lastProgress?.messages)
        assertEquals(250L, result.messages)
        // Every other message is from "me"
        assertEquals(125L, result.ownMessages)
        assertEquals(messages.last().getString("id"), result.lastMessageId)
        assertEquals(1.0, lastProgress!!.fraction, 0.0)
        tempDir.deleteRecursively()
        return output.toByteArray()
    }

    @Test
    fun singleHtmlEmbedsEveryAssetOnce() {
        val html = export(ExportOptions(ExportFormat.Html)).toString(Charsets.UTF_8)
        File("build/test-output").mkdirs()
        File("build/test-output/export.html").writeText(html)

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Direct Messages - Alice</title>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
        assertEquals("all messages rendered", 250, Regex("id=\"chatlog__message-container-\\d+\"").findAll(html).count())
        assertTrue(html.contains("Message #249 with <strong>bold</strong> and"))
        assertTrue("mentions resolve from message data", html.contains("@Alice</span>"))
        assertTrue("exported message count", html.contains("Exported 250 message(s)"))

        // Assets: 2 avatars + photo + notes + emoji; the 404 falls back to its remote URL
        val blocks = Regex("""<script type="application/octet-stream" id="dce-asset-(\d+)" data-mime="([^"]+)" data-name="([^"]+)">([^<]*)</script>""")
            .findAll(html).toList()
        assertEquals(5, blocks.size)
        assertEquals(blocks.size, blocks.map { it.groupValues[1] }.toSet().size)
        val photo = blocks.single { it.groupValues[3].startsWith("photo-") }
        assertEquals("image/png", photo.groupValues[2])
        assertTrue(Base64.getDecoder().decode(photo.groupValues[4]).contentEquals(png))
        val notes = blocks.single { it.groupValues[3].startsWith("notes-") }
        assertEquals("hello attachment", Base64.getDecoder().decode(notes.groupValues[4]).toString(Charsets.UTF_8))
        assertTrue(html.contains("$base/cdn/attachments/1/missing.png"))
        assertTrue("resolver script is included", html.contains("dce-asset-' + id"))

        // Each asset's data block follows the HTML that first references it
        Regex("""(?:src|href)="dce-asset:(\d+)"""").findAll(html).groupBy { it.groupValues[1] }.forEach { (id, refs) ->
            assertTrue(html.indexOf("id=\"dce-asset-$id\"") > refs.first().range.first)
        }

        // Rendering details
        assertTrue(html.contains("chatlog__reply-author"))
        assertTrue(html.contains("Embed title"))
        assertTrue(html.contains("started a call that lasted 10 minutes"))
        assertTrue(html.contains("<span>👍</span>"))
        assertTrue(html.contains("chatlog__emoji--large"))
        assertTrue("first page was retried after a 429", messageRequests.get() >= 4)
    }

    @Test
    fun zipContainsHtmlAndAssetFolder() {
        val zipBytes = export(ExportOptions(ExportFormat.Html, singleFile = false))
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val htmlName = "Direct Messages - Alice [100].html"
        val html = entries[htmlName]!!.toString(Charsets.UTF_8)
        val assetEntries = entries.keys.filter { it.startsWith("$htmlName" + "_Files/") }
        assertEquals(5, assetEntries.size)
        assertFalse(html.contains("dce-asset:"))
        assertTrue(html.contains("src=\"Direct%20Messages%20-%20Alice%20%5B100%5D.html_Files/photo-"))
        assertTrue(entries[assetEntries.single { "photo-" in it }]!!.contentEquals(png))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test
    fun jsonWithoutDownloadsIsOneFileLinkingToDiscord() {
        val root = JSONObject(export(ExportOptions(ExportFormat.Json, downloadAssets = false)).toString(Charsets.UTF_8))
        assertEquals(0, assetRequests.get())

        assertEquals("Direct Messages", root.getJSONObject("guild").getString("name"))
        assertEquals("DirectTextChat", root.getJSONObject("channel").getString("type"))
        assertEquals(250, root.getInt("messageCount"))
        val messages = root.getJSONArray("messages")
        assertEquals(250, messages.length())

        val first = messages.getJSONObject(0)
        assertEquals("Default", first.getString("type"))
        // ISO 8601 with the device's offset, like the desktop app
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), java.time.OffsetDateTime.parse(first.getString("timestamp")).toInstant())
        assertTrue(first.getString("timestamp"), Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d\d:\d\d""").matches(first.getString("timestamp")))
        // Mentions are resolved, other markdown is kept as written (like the desktop app)
        assertEquals("Message #0 with **bold** and @Alice", first.getString("content"))
        assertEquals("me", first.getJSONObject("author").getString("name"))
        assertEquals("0000", first.getJSONObject("author").getString("discriminator"))
        assertEquals("$base/cdn/attachments/1/photo.png?ex=1&is=2&hm=3", first.getJSONArray("attachments").getJSONObject(0).getString("url"))

        val emojiMessage = messages.getJSONObject(2)
        assertEquals(":wave: :wave:", emojiMessage.getString("content"))
        assertEquals(1, emojiMessage.getJSONArray("inlineEmojis").length())

        val reply = messages.getJSONObject(4)
        assertEquals("Reply", reply.getString("type"))

        assertEquals("Started a call that lasted 10 minutes.", messages.getJSONObject(6).getString("content"))

        val reaction = messages.getJSONObject(7).getJSONArray("reactions").getJSONObject(0)
        assertEquals(2, reaction.getInt("count"))
        assertEquals("alice", reaction.getJSONArray("users").getJSONObject(0).getString("name"))
        assertTrue(reaction.getJSONObject("emoji").getString("imageUrl").endsWith("/1f44d.svg"))

        val embed = messages.getJSONObject(5).getJSONArray("embeds").getJSONObject(0)
        assertEquals("#5865F2", embed.getString("color"))
        assertEquals("Embed *description*", embed.getString("description"))
    }

    @Test
    fun jsonWithDownloadsIsZipWithFilesFolder() {
        val entries = unzip(export(ExportOptions(ExportFormat.Json)))
        val name = "Direct Messages - Alice [100].json"
        val root = JSONObject(entries[name]!!.toString(Charsets.UTF_8))
        val photoUrl = root.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("url")
        assertTrue(photoUrl, photoUrl.startsWith("Direct%20Messages%20-%20Alice%20%5B100%5D.json_Files/photo-"))
        val photo = entries.keys.single { it.startsWith("${name}_Files/photo-") }
        assertTrue(entries[photo]!!.contentEquals(png))
        // The failed download keeps its original link
        assertTrue(entries[name]!!.toString(Charsets.UTF_8).contains("$base/cdn/attachments/1/missing.png"))
    }

    @Test
    fun xmlIsWellFormedAndComplete() {
        val bytes = export(ExportOptions(ExportFormat.Xml, downloadAssets = false))
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
        val root = document.documentElement
        assertEquals("export", root.tagName)
        val messages = root.getElementsByTagName("message")
        assertEquals(250, messages.length)
        assertEquals("250", root.getElementsByTagName("messageCount").item(0).textContent)

        val first = messages.item(0) as org.w3c.dom.Element
        assertEquals("Message #0 with **bold** and @Alice", first.getElementsByTagName("content").item(0).textContent)
        assertEquals("photo.png", first.getElementsByTagName("attachment").item(0).let { (it as org.w3c.dom.Element).getElementsByTagName("fileName").item(0).textContent })
        assertEquals(0, assetRequests.get())
    }

    @Test(expected = CancelledException::class)
    fun cancellationStopsTheExport() {
        export(ExportOptions(ExportFormat.Html)) { messageRequests.get() > 2 }
    }
}
