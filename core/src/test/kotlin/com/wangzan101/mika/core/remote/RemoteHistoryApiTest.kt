package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class RemoteHistoryApiTest {
    private var allowed = false
    private var reads = 0
    private var revokeDuringRead = false
    private val source = object : RemoteHistory {
        override val enabled get() = allowed
        private fun page(offset: Int, limit: Int): JsonObject {
            reads++
            if (revokeDuringRead) allowed = false
            return buildJsonObject { put("offset", offset); put("limit", limit) }
        }
        override fun conversations(offset: Int, limit: Int) = page(offset, limit)
        override fun turns(conversation: String, offset: Int, limit: Int) =
            if (conversation == "missing") null else page(offset, limit)
        override fun run(conversation: String, runId: String, offset: Int, limit: Int) = page(offset, limit)
        override fun body(conversation: String, runId: String, spanId: String, offset: Int, limit: Int) = page(offset, limit)
    }
    private val host = RemoteHost(FakeRemoteTasks(), { "test-owner" }, 0, false, "test", history = source)
    private val client = OkHttpClient()
    @Before fun start() = host.start()
    @After fun stop() { host.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    private fun get(path: String, token: String = "test-owner", origin: Boolean = false, post: Boolean = false): Pair<Int, JsonObject> {
        val b = Request.Builder().url("http://127.0.0.1:${host.boundPort}/api/v1$path").header("Authorization", "Bearer $token")
        if (origin) b.header("Origin", "http://example.test")
        if (post) b.post("{}".toRequestBody())
        return client.newCall(b.build()).execute().use { it.code to Json.parseToJsonElement(it.body!!.string()).jsonObject }
    }

    @Test fun historyRequiresBothConnectionAuthenticationAndPhoneConsent() {
        assertFalse(get("").second.getValue("history_enabled").jsonPrimitive.boolean)
        assertEquals(403, get("/history/conversations").first)
        allowed = true
        assertEquals(401, get("/history/conversations", token = "wrong").first)
        assertEquals(403, get("/history/conversations", origin = true).first)
        assertEquals(0, reads)
        assertEquals(200, get("/history/conversations").first)
        allowed = false
        assertEquals(403, get("/history/conversations/c/runs/r/bodies/0123456789abcdef").first)
        assertEquals(1, reads)
    }

    @Test fun routesOnlyBoundedReadsAndDoesNotMakePhoneTasksControllable() {
        allowed = true
        val page = get("/history/conversations/c/runs/r?offset=40&limit=10")
        assertEquals(200, page.first)
        assertEquals(40, page.second.getValue("offset").jsonPrimitive.int)
        assertEquals(10, page.second.getValue("limit").jsonPrimitive.int)
        assertEquals(3000, get("/history/conversations/c/runs/r/bodies/0123456789abcdef").second.getValue("limit").jsonPrimitive.int)
        for (query in listOf("offset=-1", "limit=51", "limit=0", "limit=1&limit=2", "unknown=1"))
            assertEquals(query, 400, get("/history/conversations?$query").first)
        assertEquals(405, get("/history/conversations", post = true).first)
        assertEquals(404, get("/history/conversations/missing").first)
        assertEquals(404, get("/history/conversations/c/runs/r/bodies/not-a-span").first)
        assertEquals(404, get("/tasks/phone-only").first)
    }

    @Test fun revokingWhileReadingDiscardsTheResponse() {
        allowed = true
        revokeDuringRead = true
        val result = get("/history/conversations")
        assertEquals(403, result.first)
        assertFalse(result.second.containsKey("offset"))
    }

    @Test fun relayRequestsDoNotInheritLocalHistoryPermission() {
        allowed = true
        val endpoints = RemoteEndpoints(FakeRemoteTasks(), "test", history = source)
        fun request(path: String) = HttpRequest("GET", path, emptyMap(), byteArrayOf(), InetAddress.getLoopbackAddress())
        for (deviceAccess in listOf(true, false)) {
            val local = endpoints.handle(request("/api/v1"), deviceAccess = deviceAccess)
            assertTrue(Json.parseToJsonElement(local.body.decodeToString()).jsonObject.getValue("history_enabled").jsonPrimitive.boolean)
            val relay = endpoints.handle(request("/api/v1"), deviceAccess = deviceAccess, historyAccess = false)
            val info = Json.parseToJsonElement(relay.body.decodeToString()).jsonObject
            assertFalse(info.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "history" })
            assertFalse(info.containsKey("history_enabled"))
            assertEquals(404, endpoints.handle(request("/api/v1/history/conversations"), deviceAccess = deviceAccess, historyAccess = false).status)
        }
        assertEquals(0, reads)
    }
}
