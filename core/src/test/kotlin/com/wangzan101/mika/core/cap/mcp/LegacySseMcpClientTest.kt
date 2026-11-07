package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LegacySseMcpClientTest {
    /** 极简的 2024-11-05 服务:GET 开 SSE,POST 回 202,响应从 SSE 推回来。 */
    private class SseServer(private val strictIds: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        @Volatile private var sseOut: OutputStream? = null
        @Volatile private var sseSocket: Socket? = null
        private val json = Json { ignoreUnknownKeys = true }
        private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val port: Int get() = server.localPort

        init {
            pool.submit {
                val get = try { server.accept() } catch (e: Exception) { return@submit }
                pool.submit { serveSse(get) }
                while (!server.isClosed) {
                    val post = try { server.accept() } catch (e: Exception) { break }
                    pool.submit { handlePost(post) }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:$port$path"

        private fun serveSse(socket: Socket) {
            sseSocket = socket
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                readHeaders(reader)
                val out = it.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
                out.flush()
                sseOut = out
                send(out, "endpoint", url("/messages"))
                val input: InputStream = it.getInputStream()
                while (input.read() >= 0) Unit
            }
        }

        private fun handlePost(socket: Socket) {
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("POST ")) return
                val headers = readHeaders(reader)
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(chars, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val out = it.getOutputStream()
                out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val message = runCatching { json.parseToJsonElement(String(chars, 0, read)) as JsonObject }.getOrNull() ?: return
                val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: return
                val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return
                val result = when (method) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", "2024-11-05")
                        put("serverInfo", buildJsonObject { put("name", "legacy") })
                        put("instructions", "legacy server")
                    }
                    "tools/list" -> buildJsonObject {
                        put("tools", kotlinx.serialization.json.JsonArray(listOf(
                            buildJsonObject {
                                put("name", "search")
                                put("description", "Search")
                                put("inputSchema", buildJsonObject { put("type", "object") })
                            },
                        )))
                    }
                    "tools/call" -> buildJsonObject {
                        put("content", kotlinx.serialization.json.JsonArray(listOf(
                            buildJsonObject { put("type", "text"); put("text", "found") },
                        )))
                    }
                    else -> return
                }
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id.toLong())
                    if (strictIds && !seen.add(id)) put("error", buildJsonObject {
                        put("code", -32600); put("message", "request ID reused")
                    }) else put("result", result)
                }
                synchronized(this) {
                    sseOut?.let { stream ->
                        send(stream, "message", response.toString())
                    }
                }
            }
        }

        private fun readHeaders(reader: java.io.BufferedReader): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                headers[name] = line.substringAfter(':').trim()
            }
            return headers
        }

        private fun send(out: OutputStream, event: String, data: String) {
            out.write("event: $event\ndata: $data\n\n".toByteArray())
            out.flush()
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { sseSocket?.close() }
            runCatching { sseOut?.close() }
            pool.shutdownNow()
        }
    }

    @Test(timeout = 20_000) fun connectsListsAndCallsOverLegacySse() = runBlocking {
        SseServer().use { server ->
            val endpoint = LegacySseMcpEndpoint.connect(
                "legacy", server.url("/mcp"), OkHttpClient(), timeoutSec = 5,
            ).getOrThrow()
            assertEquals("legacy", endpoint.serverName)
            assertEquals("legacy server", endpoint.instructions)
            assertEquals(listOf("search"), endpoint.tools().map { it.name })

            val result = endpoint.call("search", buildJsonObject { put("q", "x") })
            assertFalse(result.isError)
            assertEquals("found", result.text)
            endpoint.close()
        }
    }

    @Test(timeout = 20_000) fun requestIdsRemainUniqueAfterHandshake() = runBlocking {
        SseServer(strictIds = true).use { server ->
            val endpoint = LegacySseMcpEndpoint.connect("strict", server.url("/mcp"),
                OkHttpClient(), timeoutSec = 5).getOrThrow()
            try {
                kotlinx.coroutines.coroutineScope {
                    (1..8).map { async {
                        endpoint.call("search", buildJsonObject {})
                    } }.forEach { assertEquals("found", it.await().text) }
                }
            } finally { endpoint.close() }
        }
    }

    @Test(timeout = 20_000) fun missingEndpointEventFailsRatherThanHangs() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                val socket = server.accept()
                socket.use {
                    val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray(),
                    )
                    it.getOutputStream().flush()
                    Thread.sleep(2_000)
                }
            }
            val result = LegacySseMcpEndpoint.connect(
                "no-endpoint", "http://127.0.0.1:${server.localPort}/mcp", OkHttpClient(), timeoutSec = 1,
            )
            org.junit.Assert.assertTrue(result.isFailure)
            org.junit.Assert.assertTrue(
                result.exceptionOrNull()?.message.orEmpty(),
                result.exceptionOrNull()?.message.orEmpty().contains("did not provide an endpoint"),
            )
        } finally {
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }
}
