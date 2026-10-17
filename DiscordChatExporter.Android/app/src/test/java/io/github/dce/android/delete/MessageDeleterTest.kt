package io.github.dce.android.delete

import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.export.FakeHttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Deletes messages through a local fake of the Discord API. */
class MessageDeleterTest {
    private lateinit var server: FakeHttpServer
    private lateinit var client: DiscordClient
    private val messages = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val deleteRequests = AtomicInteger()

    private fun snowflake(instant: Instant) = ((instant.toEpochMilli() - 1420070400000L) shl 22).toString()

    private fun user(id: String) = JSONObject().put("id", id).put("username", "user$id").put("discriminator", "0")

    private fun json(value: Any, code: Int = 200) =
        FakeHttpServer.Response(code, value.toString().toByteArray(), "application/json")

    private fun handle(request: FakeHttpServer.Request): FakeHttpServer.Response {
        if (request.header("Authorization") != "secret") return json(JSONObject(), 401)
        val path = request.path.removePrefix("/api/")
        return when {
            path == "users/@me" -> json(user("1"))
            path == "channels/100/messages" && request.method == "GET" -> {
                val q = request.query
                val limit = q["limit"]!!.toInt()
                val sorted = synchronized(messages) { messages.sortedBy { it.getString("id").toULong() } }
                val page = when {
                    q["after"] != null -> sorted.filter { it.getString("id").toULong() > q["after"]!!.toULong() }.take(limit)
                    q["before"] != null -> sorted.filter { it.getString("id").toULong() < q["before"]!!.toULong() }.takeLast(limit)
                    else -> sorted.takeLast(limit)
                }
                json(JSONArray(page.reversed()))
            }
            path.startsWith("channels/100/messages/") && request.method == "DELETE" -> {
                val id = path.substringAfterLast('/')
                // The first deletion is rate limited once, to exercise the retry path
                if (deleteRequests.getAndIncrement() == 0) {
                    return FakeHttpServer.Response(429, "{}".toByteArray(), "application/json", mapOf("Retry-After" to "0"))
                }
                val message = synchronized(messages) { messages.firstOrNull { it.getString("id") == id } }
                when {
                    message == null -> json(JSONObject().put("code", 10008), 404)
                    message.getJSONObject("author").getString("id") != "1" -> json(JSONObject().put("code", 50003), 403)
                    // Pretend this one is protected some other way, e.g. an old message Discord won't let go of
                    message.getString("content") == "protected" -> json(JSONObject().put("code", 50021), 403)
                    else -> {
                        messages.remove(message)
                        FakeHttpServer.Response(204, ByteArray(0), "application/json")
                    }
                }
            }
            else -> json(JSONObject(), 404)
        }
    }

    @Before
    fun setUp() {
        server = FakeHttpServer(::handle)
        server.start()
        client = DiscordClient("secret", CancellationSignal { false }, "http://127.0.0.1:${server.port}/api/")

        // 250 messages -> 3 pages; every third one is from someone else, one is a call log, one is protected
        val start = Instant.parse("2024-01-01T12:00:00Z")
        repeat(250) { i ->
            val timestamp = start.plusSeconds(i * 60L)
            messages += JSONObject()
                .put("id", snowflake(timestamp))
                .put("type", if (i == 10) 3 else 0)
                .put("author", user(if (i % 3 == 0) "2" else "1"))
                .put("timestamp", timestamp.toString())
                .put("content", if (i == 20) "protected" else "Message #$i")
        }
    }

    @After
    fun tearDown() = server.stop()

    private fun ids(predicate: (JSONObject) -> Boolean) =
        synchronized(messages) { messages.filter(predicate).map { it.getString("id") } }

    @Test
    fun deletesOnlyOwnDeletableMessagesUpToTheExport() {
        // The export covered the first 200 messages; the last 50 were sent afterwards
        val upTo = messages[199].getString("id")
        val newerOwn = ids { it.getString("id").toULong() > upTo.toULong() && it.getJSONObject("author").getString("id") == "1" }
        val others = ids { it.getJSONObject("author").getString("id") == "2" }
        val sleeps = mutableListOf<Long>()
        var lastProgress: DeletionProgress? = null

        val result = MessageDeleter(client, CancellationSignal { false }, sleep = { sleeps += it }, random = Random(1))
            .run("100", "1", upTo, 2000L) { lastProgress = it }

        // Own messages among the first 200: 200 - 67 others = 133; the call log is skipped up front and one is refused
        assertEquals(131L, result.deleted)
        assertEquals(1L, result.skipped)
        assertEquals(200L, result.scanned)
        assertEquals(1.0, result.fraction, 0.0)
        assertEquals(result, lastProgress)

        val remaining = ids { true }
        assertTrue(remaining.containsAll(others))
        assertTrue(remaining.containsAll(newerOwn))
        assertTrue(messages[10].getString("id") in remaining)
        assertTrue(messages[20].getString("id") in remaining)
        assertEquals(250 - 131, remaining.size)

        // One pause per deletion attempt, at the chosen pace plus at most 20% jitter
        assertEquals(132, sleeps.size)
        assertTrue(sleeps.all { it in 2000L..2400L })
    }

    @Test
    fun intervalHasAFloor() {
        val sleeps = mutableListOf<Long>()
        MessageDeleter(client, CancellationSignal { false }, sleep = { sleeps += it })
            .run("100", "1", null, 10L) {}
        assertTrue(sleeps.isNotEmpty())
        assertTrue(sleeps.all { it >= MessageDeleter.MIN_INTERVAL_MILLIS })
    }

    @Test
    fun stoppingAndRestartingFinishesTheJob() {
        val stop = AtomicBoolean(false)
        var deletions = 0
        val signal = CancellationSignal { stop.get() }
        try {
            MessageDeleter(client, signal, sleep = { if (++deletions == 30) stop.set(true) })
                .run("100", "1", null, 1000L) {}
            error("Should have been stopped")
        } catch (_: CancelledException) {
        }
        assertEquals(250 - 30, messages.size)

        val result = MessageDeleter(client, CancellationSignal { false }, sleep = {}).run("100", "1", null, 1000L) {}
        // 250 - 84 others = 166 own; minus the call log and the protected one
        assertEquals(166L - 2 - 30, result.deleted)
        assertEquals(84 + 2, messages.size)
    }

    @Test
    fun emptyChannelIsDoneImmediately() {
        messages.clear()
        val result = MessageDeleter(client, CancellationSignal { false }, sleep = {}).run("100", "1", null, 1000L) {}
        assertEquals(0L, result.deleted)
        assertEquals(1.0, result.fraction, 0.0)
    }
}
