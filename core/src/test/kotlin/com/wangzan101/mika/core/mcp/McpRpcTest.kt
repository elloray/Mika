package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.HttpResponse
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class McpRpcTest {
    private val fake = FakeRemoteTasks()
    private val seen = mutableListOf<String?>()
    private val rpc = McpRpc(McpTaskTools(fake), "0.1.test") { _, name -> seen += name }

    private fun post(body: String, headers: Map<String, String> = emptyMap(), method: String = "POST"): HttpResponse =
        rpc.handle(HttpRequest(method, "/mcp",
            (mapOf("content-type" to "application/json", "accept" to "application/json, text/event-stream") + headers).mapKeys { it.key.lowercase() },
            body.toByteArray(), InetAddress.getLoopbackAddress()))

    private fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
    private fun HttpResponse.errorCode() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    private fun modernMeta(version: String = McpRpc.MODERN) =
        """"_meta":{"io.modelcontextprotocol/protocolVersion":"$version","io.modelcontextprotocol/clientInfo":{"name":"desk","version":"1"},"io.modelcontextprotocol/clientCapabilities":{}}"""

    private fun modernHeaders(method: String, version: String = McpRpc.MODERN, name: String? = null) =
        mapOf("MCP-Protocol-Version" to version, "Mcp-Method" to method) + (name?.let { mapOf("Mcp-Name" to it) } ?: emptyMap())

    // ---------- 老一代:握手 ----------

    @Test fun initializeNegotiatesALegacyVersion() {
        val r = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"desk","version":"1"}}}""")
        assertEquals(200, r.status)
        val result = r.json()["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("mika", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        assertNull("no sessions", r.headers["Mcp-Session-Id"])
        assertEquals(listOf<String?>("desk"), seen)
        // 不认的版本给最新的老一代版本
        val other = post("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-01-01","capabilities":{}}}""")
        assertEquals(McpRpc.LEGACY_VERSIONS.first(), other.json()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test fun notificationsAreAcceptedWithoutBody() {
        val r = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.status)
        assertEquals(0, r.body.size)
    }

    @Test fun legacyToolsListAndCall() {
        val list = post("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""", mapOf("MCP-Protocol-Version" to "2025-06-18"))
        assertEquals(200, list.status)
        val names = list.json()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("mika_run_task" in names && "mika_answer" in names)
        assertEquals("a", list.json()["id"]!!.jsonPrimitive.content)

        val call = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mika_run_task","arguments":{"goal":"check battery"}}}""")
        val result = call.json()["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("t1", result["structuredContent"]!!.jsonObject["task_id"]!!.jsonPrimitive.content)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("t1", Json.parseToJsonElement(text).jsonObject["task_id"]!!.jsonPrimitive.content)
    }

    @Test fun badArgumentsAreToolErrorsNotProtocolErrors() {
        val r = post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"mika_task_status","arguments":{}}}""")
        assertEquals(200, r.status)
        val result = r.json()["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("task_id"))
    }

    @Test fun answeringAConfirmationIsAToolError() {
        fake.start("pay", null)
        fake.set("t1", TaskState.WaitingTakeover("payment", "Pay now"))
        val r = post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"mika_answer","arguments":{"task_id":"t1","text":"approve"}}}""")
        val result = r.json()["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(fake.answers.isEmpty())
    }

    @Test fun protocolErrors() {
        assertEquals(McpRpc.PARSE_ERROR, post("{not json").errorCode())
        assertEquals(400, post("{not json").status)
        assertEquals(McpRpc.INVALID_REQUEST, post("""{"jsonrpc":"1.0","id":1,"method":"ping"}""").errorCode())
        assertEquals(McpRpc.INVALID_REQUEST, post("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""").errorCode())
        assertEquals(McpRpc.INVALID_REQUEST, post("""{"jsonrpc":"2.0","id":null,"method":"ping"}""").errorCode())
        post("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""").let {
            assertEquals(200, it.status); assertEquals(McpRpc.METHOD_NOT_FOUND, it.errorCode())
        }
        assertEquals(McpRpc.INVALID_PARAMS, post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"rm_rf"}}""").errorCode())
        assertEquals(McpRpc.INVALID_PARAMS,
            post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mika_list_tasks","arguments":[1]}}""").errorCode())
        assertEquals(405, post("", method = "GET").status)
        assertEquals(405, post("", method = "DELETE").status)
        assertEquals(415, post("{}", mapOf("content-type" to "text/plain")).status)
        assertEquals(400, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("MCP-Protocol-Version" to "1999-01-01")).status)
    }

    // ---------- 新一代:每个请求自带版本 ----------

    @Test fun modernDiscoverAndToolsList() {
        val d = post("""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{${modernMeta()}}}""", modernHeaders("server/discover"))
        assertEquals(200, d.status)
        val result = d.json()["result"]!!.jsonObject
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertTrue(result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(McpRpc.MODERN))
        assertEquals("mika", result["_meta"]!!.jsonObject[McpRpc.META_SERVER]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val l = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{${modernMeta()}}}""", modernHeaders("tools/list"))
        assertEquals(7, l.json()["result"]!!.jsonObject["tools"]!!.jsonArray.size)

        // 新一代规定这两种完整结果必须带缓存提示,缺了客户端(如 Claude Code)整个拒收
        for (r in listOf(result, l.json()["result"]!!.jsonObject)) {
            assertTrue(r["ttlMs"]!!.jsonPrimitive.content.toLong() >= 0)
            assertEquals("private", r["cacheScope"]!!.jsonPrimitive.content)
        }
    }

    @Test fun modernToolCallChecksMirroredHeaders() {
        val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"mika_list_tasks","arguments":{},${modernMeta()}}}"""
        post(body, modernHeaders("tools/call", name = "mika_list_tasks")).let {
            assertEquals(200, it.status)
            assertFalse("工具调用的结果不可缓存,不带缓存提示", "ttlMs" in it.json()["result"]!!.jsonObject)
        }
        val encoded = "=?base64?" + Base64.getEncoder().encodeToString("mika_list_tasks".toByteArray()) + "?="
        assertEquals(200, post(body, modernHeaders("tools/call", name = encoded)).status)
        post(body, modernHeaders("tools/call", name = "mika_run_task")).let {
            assertEquals(400, it.status); assertEquals(McpRpc.HEADER_MISMATCH, it.errorCode())
        }
        assertEquals(McpRpc.HEADER_MISMATCH, post(body, modernHeaders("tools/call")).errorCode())
        assertEquals(McpRpc.HEADER_MISMATCH, post(body, modernHeaders("tools/list", name = "mika_list_tasks")).errorCode())
        assertEquals(McpRpc.HEADER_MISMATCH, post(body, mapOf("Mcp-Method" to "tools/call", "Mcp-Name" to "mika_list_tasks")).errorCode())
    }

    @Test fun modernUnsupportedVersionListsSupportedOnes() {
        val r = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{${modernMeta("1900-01-01")}}}""", modernHeaders("tools/list", "1900-01-01"))
        assertEquals(400, r.status)
        assertEquals(McpRpc.UNSUPPORTED_VERSION, r.errorCode())
        val supported = r.json()["error"]!!.jsonObject["data"]!!.jsonObject["supported"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(McpRpc.MODERN in supported && "2025-06-18" in supported)
    }

    @Test fun modernUnknownMethodIs404AndMissingCapabilitiesIs400() {
        post("""{"jsonrpc":"2.0","id":1,"method":"prompts/list","params":{${modernMeta()}}}""", modernHeaders("prompts/list")).let {
            assertEquals(404, it.status); assertEquals(McpRpc.METHOD_NOT_FOUND, it.errorCode())
        }
        val noCaps = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"${McpRpc.MODERN}"}}}"""
        post(noCaps, modernHeaders("tools/list")).let { assertEquals(400, it.status); assertEquals(McpRpc.INVALID_PARAMS, it.errorCode()) }
        // 头里是新一代版本、正文却没有 _meta
        post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", modernHeaders("tools/list")).let {
            assertEquals(400, it.status); assertEquals(McpRpc.INVALID_PARAMS, it.errorCode())
        }
    }
}
