package com.wangzan101.mika.core.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 原始 TCP 服务发完 SSE 后故意不关连接:协议终态必须让客户端立即收尾,非终态和截断的语义不变。 */
class HttpSupportStreamTerminalTest {
    private val servers = mutableListOf<HeldSseServer>()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))

    @After fun tearDown() { servers.forEach(Closeable::close) }

    private fun server(vararg events: String): HeldSseServer =
        HeldSseServer(events.toList()).also(servers::add)

    private fun http() = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun complete(model: StreamingChatModel, server: HeldSseServer, onText: (String) -> Unit = {}): ModelReply {
        val reply = withTimeout(3_000) { model.completeStreaming("s", user, emptyList(), onText) }
        server.awaitClientClosed()
        return reply
    }

    @Test fun openAiDoneFinishesWithoutEofAndKeepsWholeEvent() = runBlocking {
        val server = server(
            "{\"choices\":[{\"delta\":\n{\"content\":\"完整\"}}]}",
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2}}""",
            "[DONE]",
        )
        val model = OpenAiCompatModel("${server.baseUrl}/v1", "k", "m", caps, http())
        val updates = Collections.synchronizedList(mutableListOf<String>())
        val reply = complete(model, server, updates::add)
        assertEquals(listOf("完整"), updates)
        assertEquals(listOf(Part.Text("完整")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(9, 2), reply.usage)
    }

    @Test fun anthropicMessageStopFinishesWithoutEof() = runBlocking {
        val server = server(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完整"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
            """{"type":"message_stop"}""",
        )
        val model = AnthropicModel(server.baseUrl, "k", "m", caps, http())
        val reply = complete(model, server)
        assertEquals(listOf(Part.Text("完整")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(10, 3), reply.usage)
    }

    @Test fun responsesCompletedFinishesWithoutEof() = runBlocking {
        val server = server(
            """{"type":"response.output_text.delta","delta":"完整"}""",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":4,"input_tokens_details":{"cached_tokens":8}}}}""",
        )
        val model = ResponsesModel(server.baseUrl, BearerToken.fixed("k"), "m", caps, http())
        val reply = complete(model, server)
        assertEquals(listOf(Part.Text("完整")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(12, 4, 8), reply.usage)
    }

    @Test fun geminiReadsTrailingUsageBeforeFinishingWithoutEof() = runBlocking {
        val server = server(
            """{"candidates":[{"content":{"parts":[{"text":"你"}]}}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":1}}""",
            """{"candidates":[{"content":{"parts":[{"text":"好"}]},"finishReason":"STOP"}]}""",
            """{"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":4}}""",
        )
        val model = GeminiModel(server.baseUrl, "k", "m", caps, http())
        val updates = Collections.synchronizedList(mutableListOf<String>())
        val reply = complete(model, server, updates::add)
        assertEquals(listOf("你", "你好"), updates)
        assertEquals(listOf(Part.Text("你好")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(11, 4), reply.usage)
    }

    @Test fun nonTerminalEventDoesNotFinishAndEofIsStillTruncated() = runBlocking {
        supervisorScope {
            val server = server("""{"choices":[{"delta":{"content":"半句"}}]}""")
            val model = OpenAiCompatModel("${server.baseUrl}/v1", "k", "m", caps, http())
            val textSeen = CountDownLatch(1)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { model.completeStreaming("s", user, emptyList()) { textSeen.countDown() } }
            server.awaitRequest()
            assertTrue("非终态事件没有交付", textSeen.await(3, TimeUnit.SECONDS))
            assertTrue("非终态事件不能让挂住的连接提前完成", pending.isActive)
            server.endResponse()
            try {
                withTimeout(3_000) { pending.await() }
                fail("没有终态的 EOF 应报截断")
            } catch (_: ModelError.StreamCut) {
                // 原有截断语义不变
            }
        }
    }

    /** 一次请求一个连接;响应不发送 EOF,而是等服务端读到客户端关闭连接。 */
    private class HeldSseServer(private val events: List<String>) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "held-sse-server").apply { isDaemon = true }
        }
        private val requestRead = CountDownLatch(1)
        @Volatile private var peer: Socket? = null
        private val task = executor.submit { serve() }

        val baseUrl = "http://${listener.inetAddress.hostAddress}:${listener.localPort}"

        fun awaitRequest() {
            assertTrue("客户端没有发来请求", requestRead.await(3, TimeUnit.SECONDS))
        }

        fun awaitClientClosed() {
            try {
                task.get(3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw AssertionError("客户端没有在协议终态后及时关闭连接", e)
            }
        }

        /** 只关闭响应方向,给客户端一个真实 EOF;用于截断路径。 */
        fun endResponse() { peer?.shutdownOutput() }

        override fun close() {
            listener.close()
            peer?.close()
            executor.shutdownNow()
        }

        private fun serve() {
            try {
                listener.accept().use { socket ->
                    peer = socket
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    readRequest(input)
                    requestRead.countDown()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: keep-alive\r\n\r\n")
                        events.forEach { event ->
                            event.split('\n').forEach { line -> append("data: ").append(line).append('\n') }
                            append('\n')
                        }
                    }
                    socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    while (input.read() != -1) {
                        // 保持连接,直到客户端按协议终态主动关闭
                    }
                }
            } finally {
                requestRead.countDown()
            }
        }

        private fun readRequest(input: InputStream) {
            check(readLine(input).startsWith("POST "))
            var contentLength = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val header = line.split(':', limit = 2)
                if (header.size == 2 && header[0].equals("Content-Length", ignoreCase = true)) {
                    contentLength = header[1].trim().toInt()
                }
            }
            repeat(contentLength) { check(input.read() != -1) }
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) throw EOFException("请求头不完整")
                if (next == '\n'.code) return String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(next)
            }
        }
    }
}
