package io.github.dce.android.export

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

/** Minimal HTTP/1.1 server for tests (one request per connection), since com.sun.net.httpserver isn't on the Android test classpath. */
class FakeHttpServer(private val handler: (Request) -> Response) {
    class Request(val method: String, val uri: URI, val headers: Map<String, String>) {
        val path: String get() = uri.path
        val query: Map<String, String>
            get() = uri.rawQuery.orEmpty().split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }

        fun header(name: String) = headers[name.lowercase()]
    }

    class Response(val code: Int, val body: ByteArray, val contentType: String, val headers: Map<String, String> = emptyMap())

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    fun start() {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }

        val requestLine = readLine().split(' ')
        if (requestLine.size < 2) return
        val headers = generateSequence(::readLine).takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val response = handler(Request(requestLine[0], URI(requestLine[1]), headers))

        val head = buildString {
            append("HTTP/1.1 ${response.code} X\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(response.body)
            flush()
        }
    }

    fun stop() = socket.close()
}
