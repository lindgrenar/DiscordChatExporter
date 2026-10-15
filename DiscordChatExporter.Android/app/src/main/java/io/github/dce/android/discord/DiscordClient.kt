package io.github.dce.android.discord

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.time.Duration

class DiscordException(message: String, val isFatal: Boolean = false) : Exception(message)

class CancelledException : Exception("Export cancelled.")

/** Cooperative cancellation, checked between requests and while waiting out rate limits. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    fun throwIfCancelled() {
        if (isCancelled()) throw CancelledException()
    }

    fun sleep(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            throwIfCancelled()
            val step = minOf(remaining, 250L)
            Thread.sleep(step)
            remaining -= step
        }
        throwIfCancelled()
    }
}

/** Shared HTTP plumbing, mirroring DiscordChatExporter.Core/Utils/Http.cs retry semantics. */
object Http {
    private const val USER_AGENT = "DiscordChatExporter-Android/0.1"

    class Response(val code: Int, val headers: Map<String, String>, val connection: HttpURLConnection) {
        val isSuccess get() = code in 200..299
        fun stream(): InputStream = if (isSuccess) connection.inputStream else (connection.errorStream ?: "".byteInputStream())
        fun text(): String = stream().use { it.readBytes().toString(Charsets.UTF_8) }
        fun close() = connection.disconnect()
    }

    private fun isRetryable(code: Int) = code == 429 || code == 408 || code >= 500

    fun open(url: String, headers: Map<String, String>): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val code = connection.responseCode
        val responseHeaders = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
        return Response(code, responseHeaders, connection)
    }

    /**
     * Sends a GET request, retrying on network errors, 429, 408 and 5xx responses.
     * The caller owns (and must close) the returned response.
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancellation: CancellationSignal,
        maxAttempts: Int = 9,
    ): Response {
        var attempt = 0
        while (true) {
            cancellation.throwIfCancelled()
            attempt++
            val response = try {
                open(url, headers)
            } catch (e: IOException) {
                // A host that doesn't resolve isn't going to start resolving in a few seconds
                if (attempt >= maxAttempts || e is UnknownHostException) throw e
                cancellation.sleep(backoff(attempt))
                continue
            }

            if (!isRetryable(response.code) || attempt >= maxAttempts) return response

            val retryAfter = response.headers["retry-after"]?.toDoubleOrNull()
            response.close()
            val delay = if (retryAfter != null) (retryAfter * 1000).toLong() + 1000 else backoff(attempt)
            cancellation.sleep(delay)
        }
    }

    private fun backoff(attempt: Int) = (Math.pow(2.0, attempt.toDouble()).toLong() + 1) * 1000
}

class DiscordClient(
    private val token: String,
    private val cancellation: CancellationSignal = CancellationSignal { false },
    private val baseUrl: String = "https://discord.com/api/v10/",
) {
    private var authHeader: String? = null

    private fun request(path: String, auth: String): Http.Response {
        val response = Http.get(baseUrl + path, mapOf("Authorization" to auth), cancellation)

        // Discord's advisory rate limits: if this was the last request in the bucket,
        // wait out the reset (capped, as Discord sometimes reports absurd values).
        val remaining = response.headers["x-ratelimit-remaining"]?.toIntOrNull()
        val resetAfter = response.headers["x-ratelimit-reset-after"]?.toDoubleOrNull()
        if (remaining != null && remaining <= 0 && resetAfter != null) {
            val delay = Duration.ofMillis((resetAfter * 1000).toLong() + 1000)
                .coerceIn(Duration.ZERO, Duration.ofSeconds(60))
            cancellation.sleep(delay.toMillis())
        }

        return response
    }

    /** User tokens are sent as-is; bot tokens need the "Bot" prefix. Bots can't see user DMs, but support them anyway. */
    private fun resolveAuth(): String {
        authHeader?.let { return it }
        for (candidate in listOf(token, "Bot $token")) {
            val response = request("users/@me", candidate)
            val code = response.code
            response.close()
            if (code != 401) {
                authHeader = candidate
                return candidate
            }
        }
        throw DiscordException("Authentication token is invalid.", isFatal = true)
    }

    private fun getText(path: String): String {
        val response = request(path, resolveAuth())
        try {
            if (response.isSuccess) return response.text()
            throw when (response.code) {
                401 -> DiscordException("Authentication token is invalid.", isFatal = true)
                403 -> DiscordException("Request to '$path' failed: forbidden.")
                404 -> DiscordException("Request to '$path' failed: not found.")
                else -> DiscordException("Request to '$path' failed: HTTP ${response.code}.\n${response.text().take(500)}", isFatal = true)
            }
        } finally {
            response.close()
        }
    }

    private fun tryGetText(path: String): String? {
        val response = request(path, resolveAuth())
        try {
            return if (response.isSuccess) response.text() else null
        } finally {
            response.close()
        }
    }

    fun getCurrentUser(): User = User.parse(JSONObject(getText("users/@me")))

    fun tryGetUser(id: String): User? = tryGetText("users/$id")?.let { User.parse(JSONObject(it)) }

    fun getChannel(id: String): Channel = Channel.parse(JSONObject(getText("channels/$id")))

    fun tryGetChannel(id: String): Channel? = tryGetText("channels/$id")?.let { Channel.parse(JSONObject(it)) }

    /** Open DM channels (the ones shown in the Discord sidebar), most recently active first. */
    fun getDirectMessageChannels(): List<Channel> =
        JSONArray(getText("users/@me/channels")).objects()
            .map(Channel::parse)
            .filter { it.isDirect }
            .sortedWith { a, b -> Snowflake.compare(b.lastMessageId ?: b.id, a.lastMessageId ?: a.id) }

    private fun parseMessages(text: String): List<Message> = JSONArray(text).objects().map(Message::parse)

    /**
     * Streams every message in the channel in chronological order.
     * Port of DiscordClient.GetMessagesAsync (forward direction, no range).
     */
    fun forEachMessage(channelId: String, onProgress: (Double) -> Unit, onMessage: (Message) -> Unit) {
        // Snapshot the last message so that the range is fixed and progress can be calculated
        val omega = tryGetText("channels/$channelId/messages?limit=1")
            ?.let(::parseMessages)?.lastOrNull()
            ?: return

        var alpha: Message? = null
        var boundary = "0"

        while (true) {
            cancellation.throwIfCancelled()

            // Batches come newest to oldest; reverse for chronological order
            val messages = parseMessages(getText("channels/$channelId/messages?limit=100&after=$boundary")).reversed()
            if (messages.isEmpty()) return

            for (message in messages) {
                if (Snowflake.compare(message.id, omega.id) > 0) return

                val first = alpha ?: message.also { alpha = it }
                val total = omega.timestamp.toEpochMilli() - first.timestamp.toEpochMilli()
                val fetched = message.timestamp.toEpochMilli() - first.timestamp.toEpochMilli()
                onProgress(if (total > 0) fetched.toDouble() / total else 1.0)

                onMessage(message)
            }

            boundary = messages.last().id
        }
    }
}
