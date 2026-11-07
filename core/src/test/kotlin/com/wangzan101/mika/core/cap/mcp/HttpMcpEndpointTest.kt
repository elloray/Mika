package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpMcpEndpointTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: $body\n\n")
    private fun init(session: String? = null) = json(
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"},"instructions":"read the docs"}}"""
    ).apply { session?.let { setHeader("Mcp-Session-Id", it) } }
    private fun tools(id: Long, vararg names: String) = json(
        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[${names.joinToString(",") { tool(it) }}]}}"""
    )
    private fun tool(name: String) =
        """{"name":"$name","description":"$name tool","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}"""

    @Test(timeout = 20_000) fun requestIdsRemainUniqueAcrossPaginatedCatalogsAndCalls() = runBlocking {
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = message["id"] ?: return MockResponse().setResponseCode(202)
                if (!seen.add(id.toString())) return json("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32600,"message":"request ID reused"}}""")
                val method = message.getValue("method").jsonPrimitive.content
                val secondPage = message["params"]?.jsonObject?.containsKey("cursor") == true
                val next = if (secondPage) "" else """, "nextCursor":"second""""
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{},"resources":{},"prompts":{}}}"""
                    "tools/list" -> """{"tools":[${tool(if (secondPage) "other" else "search")}]$next}"""
                    "resources/list" -> """{"resources":[{"uri":"file://${if (secondPage) "b" else "a"}"}]$next}"""
                    "prompts/list" -> """{"prompts":[{"name":"${if (secondPage) "other" else "summarize"}"}]$next}"""
                    "resources/read" -> """{"contents":[{"uri":"file://a","text":"resource"}]}"""
                    "prompts/get" -> """{"messages":[{"role":"user","content":{"type":"text","text":"prompt"}}]}"""
                    else -> """{"content":[{"type":"text","text":"found"}]}"""
                }
                return json("""{"jsonrpc":"2.0","id":$id,"result":$result}""").setHeader("Mcp-Session-Id", "strict-session")
            }
        }
        val endpoint = HttpMcpEndpoint.connect("strict", server.url("/mcp").toString(),
            OkHttpClient(), timeoutSec = 5).getOrThrow()
        try {
            assertEquals(listOf("search", "other"), endpoint.tools().map { it.name })
            assertEquals(listOf("file://a", "file://b"), endpoint.resources().map { it.uri })
            assertEquals(listOf("summarize", "other"), endpoint.prompts().map { it.name })
            assertEquals("resource", endpoint.readResource("file://a").text)
            assertEquals("user: prompt", endpoint.getPrompt("summarize", buildJsonObject {}).text)
            kotlinx.coroutines.coroutineScope {
                (1..8).map { async { endpoint.call("search", buildJsonObject {}) } }
                    .forEach { assertEquals("found", it.await().text) }
            }
            assertEquals(17, seen.size)
        } finally { endpoint.close() }
    }

    @Test fun connectsInitializesAndCalls() = runBlocking {
        server.enqueue(init("s-1"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "search"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"found"}],"isError":false}}"""))

        val endpoint = HttpMcpEndpoint.connect(
            "demo", server.url("/mcp").toString(), OkHttpClient(),
            headers = mapOf("Authorization" to "Bearer test"), timeoutSec = 5,
        ).getOrThrow()

        assertEquals("demo", endpoint.serverId)
        assertEquals("demo", endpoint.serverName)
        assertEquals("read the docs", endpoint.instructions)
        assertEquals(listOf("search"), endpoint.tools().map { it.name })
        assertTrue(endpoint.tools().single().annotations.readOnlyHint == true)

        val initRequest = server.takeRequest()
        assertEquals("POST", initRequest.method)
        assertTrue(initRequest.body.readUtf8().contains("\"initialize\""))
        assertEquals("Bearer test", initRequest.getHeader("Authorization"))
        assertNull(initRequest.getHeader("MCP-Protocol-Version"))

        val initialized = server.takeRequest()
        assertTrue(initialized.body.readUtf8().contains("notifications/initialized"))

        val listRequest = server.takeRequest()
        assertEquals("2025-03-26", listRequest.getHeader("MCP-Protocol-Version"))
        assertEquals("s-1", listRequest.getHeader("Mcp-Session-Id"))

        val result = endpoint.call("search", buildJsonObject { put("q", "x") })
        assertFalse(result.isError)
        assertEquals("found", result.text)
        val callRequest = server.takeRequest()
        val callBody = callRequest.body.readUtf8()
        assertTrue(callBody.contains("tools/call"))
        assertTrue(callBody.contains("\"q\":\"x\""))
    }

    @Test fun parsesSseResponses() = runBlocking {
        server.enqueue(sse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"sse"}}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(sse("""{"jsonrpc":"2.0","id":2,"result":{"tools":[${tool("read")}]}}"""))
        server.enqueue(sse("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"ok"}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("sse", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals("sse", endpoint.serverName)
        assertEquals(listOf("read"), endpoint.tools().map { it.name })
        assertEquals("ok", endpoint.call("read", buildJsonObject {}).text)
    }

    @Test fun followsToolsListPagination() = runBlocking {
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[${tool("a")}],"nextCursor":"page-2"}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[${tool("b")}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("paged", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals(listOf("a", "b"), endpoint.tools().map { it.name })
        server.takeRequest() // initialize
        server.takeRequest() // initialized
        val first = server.takeRequest().body.readUtf8()
        assertFalse(first.contains("page-2"))
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("page-2"))
    }

    @Test fun callErrorsBecomeResults() = runBlocking {
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "fail"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"boom"}}"""))

        val endpoint = HttpMcpEndpoint.connect("err", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        val result = endpoint.call("fail", buildJsonObject {})
        assertTrue(result.isError)
        assertTrue(result.text.contains("boom"))
    }

    @Test fun initializeHttpFailureIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val result = HttpMcpEndpoint.connect("bad", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))
    }

    @Test fun readsResourcesAndPromptsWhenAdvertised() = runBlocking {
        server.enqueue(json(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"caps"},""" +
                """"capabilities":{"tools":{},"resources":{},"prompts":{}}}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "search"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"resources":[{"uri":"file://a","name":"A","mimeType":"text/plain"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"prompts":[{"name":"summarize","description":"Sum"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":5,"result":{"contents":[{"uri":"file://a","text":"hello"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":6,"result":{"description":"Sum","messages":[{"role":"user","content":{"type":"text","text":"do it"}}]}}"""))

        val endpoint = HttpMcpEndpoint.connect("caps", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        assertEquals(listOf("file://a"), endpoint.resources().map { it.uri })
        assertEquals(listOf("summarize"), endpoint.prompts().map { it.name })
        assertEquals("hello", endpoint.readResource("file://a").text)
        assertEquals("Sum\nuser: do it", endpoint.getPrompt("summarize", buildJsonObject {}).text)
    }

    @Test fun doesNotFollowRedirects() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", "https://example.com/steal")
                .setBody("redirect"),
        )
        val result = HttpMcpEndpoint.connect("redirect", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 307"))
    }

    @Test fun closeUsesSessionId() = runBlocking {
        server.enqueue(init("s-2"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "one"))
        server.enqueue(MockResponse().setResponseCode(200))
        val endpoint = HttpMcpEndpoint.connect("close", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()
        endpoint.close()
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("s-2", delete.getHeader("Mcp-Session-Id"))
    }

    @Test fun closePreservesAuthenticationHeaders() = runBlocking {
        server.enqueue(init("authenticated-session"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "one"))
        server.enqueue(MockResponse().setResponseCode(204))
        val endpoint = HttpMcpEndpoint.connect("close-auth", server.url("/mcp").toString(),
            headers = mapOf("Authorization" to "Bearer test", "X-Api-Key" to "test-key"), timeoutSec = 5).getOrThrow()
        repeat(3) { server.takeRequest() }
        endpoint.close()
        val delete = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("DELETE", delete.method)
        assertEquals("Bearer test", delete.getHeader("Authorization"))
        assertEquals("test-key", delete.getHeader("X-Api-Key"))
        assertEquals("authenticated-session", delete.getHeader("Mcp-Session-Id"))
        assertEquals(HttpMcpEndpoint.PROTOCOL_VERSION, delete.getHeader("MCP-Protocol-Version"))
    }

    @Test fun bodyReadsStayOffTheCallerThreadDuringConnectAndCall() = runBlocking {
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "read"))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            withContext(ui) {
                val caller = Thread.currentThread()
                val readOnCaller = AtomicBoolean()
                val client = trackingClient(onRead = { if (Thread.currentThread() === caller) readOnCaller.set(true) })
                val endpoint = HttpMcpEndpoint.connect("thread", server.url("/mcp").toString(), client, timeoutSec = 5).getOrThrow()
                assertFalse("Handshake bodies must not be read on the caller thread", readOnCaller.get())
                assertEquals("ok", endpoint.call("read", buildJsonObject {}).text)
                assertFalse("Tool response bodies must not be read on the caller thread", readOnCaller.get())
            }
        }
    }

    @Test fun cancellationDuringInitializeBodyStopsReadingAndClosesResponse() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val client = trackingClient(onRead = { reading.complete(Unit) }, onClose = { closed.complete(Unit) })
        server.enqueue(init().setBodyDelay(3, TimeUnit.SECONDS))
        val connecting = async(Dispatchers.Default) {
            HttpMcpEndpoint.connect("cancel-init", server.url("/mcp").toString(), client, timeoutSec = 5)
        }
        try {
            withTimeout(5_000) { reading.await() }
            connecting.cancel()
            withTimeout(1_000) { connecting.join(); closed.await() }
        } finally {
            connecting.cancelAndJoin()
        }
    }

    @Test fun cancellationDuringToolBodyStopsReadingAndClosesResponse() = runBlocking {
        val tracking = AtomicBoolean()
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val client = trackingClient(
            onRead = { if (tracking.get()) reading.complete(Unit) },
            onClose = { if (tracking.get()) closed.complete(Unit) },
        )
        server.enqueue(init())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(tools(2, "read"))
        val endpoint = HttpMcpEndpoint.connect("cancel-call", server.url("/mcp").toString(), client, timeoutSec = 5).getOrThrow()
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[]}}""").setBodyDelay(3, TimeUnit.SECONDS))
        tracking.set(true)
        val calling = async(Dispatchers.Default) { endpoint.call("read", buildJsonObject {}) }
        try {
            withTimeout(5_000) { reading.await() }
            calling.cancel()
            withTimeout(1_000) { calling.join(); closed.await() }
        } finally {
            calling.cancelAndJoin()
        }
    }

    private fun trackingClient(onRead: () -> Unit, onClose: () -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = checkNotNull(response.body)
            response.newBuilder().body(object : ResponseBody() {
                private val stream = object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        onRead()
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        try { super.close() } finally { onClose() }
                    }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = stream
            }).build()
        }.build()
}
