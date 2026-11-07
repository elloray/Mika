package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReasoningEffortTest {
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private fun done() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")

    @Test fun apiAndChatGptRequestsOnlySetEffortWhenChosen() = runTest {
        MockWebServer().use { server ->
            server.start()
            for (plan in listOf(false, true)) for (effort in listOf(null, "low", "xhigh")) {
                server.enqueue(done())
                ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("test-key"), "test-model", caps,
                    OkHttpClient(), chatGptPlan = plan, reasoningEffort = effort).complete("", user, emptyList())
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                if (effort == null) assertFalse(body.containsKey("reasoning"))
                else assertEquals(effort, body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun includeFallbackPreservesChosenEffort() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"param":"include","message":"unsupported include"}}"""))
            server.enqueue(done())
            ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("k"), "m", caps, OkHttpClient(), reasoningEffort = "high")
                .complete("", user, emptyList())
            repeat(2) { i ->
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertEquals(i == 0, body.containsKey("include"))
            }
        }
    }

    @Test fun rejectedEffortIsReportedWithoutSilentDowngrade() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"param":"reasoning.effort","message":"Unsupported effort: high"}}"""))
            try {
                ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("k"), "m", caps, OkHttpClient(), reasoningEffort = "high")
                    .complete("", user, emptyList())
                fail("Expected an unsupported-effort error")
            } catch (e: ModelError.ReasoningEffortRejected) { assertTrue(e.detail.contains("Unsupported effort")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectedUltraKeepsTheServerDetailAndDoesNotBlameTheEndpoint() = runTest {
        MockWebServer().use { server ->
            server.start()
            val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"error":{"type":"invalid_request_error","param":"reasoning.effort","message":"$detail"}}""",
            ))
            try {
                ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("k"), "m", caps, OkHttpClient(), chatGptPlan = true,
                    reasoningEffort = "ultra").complete("", user, emptyList())
                fail("Expected a rejected-effort error")
            } catch (e: ModelError.ReasoningEffortRejected) {
                assertEquals(detail, e.detail)
            }
        }
    }

    @Test fun catalogRetainsLevelsAndDistinguishesAbsentFromEmptyMetadata() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[
                {"slug":"a","supported_reasoning_levels":[{"effort":"high"},"low",{"effort":"high"},{"effort":"future"},{"effort":3},{},3," "]},
                {"slug":"b","supported_reasoning_levels":[]},
                {"slug":"c"},
                {"slug":"d","capabilities":{"reasoning":{"supported":true,"effort":["low","high"]}}}
            ]}"""))
            val models = (ModelCatalog(OkHttpClient()).openAiCompat(server.url("/").toString(), null) as ModelListResult.Ok).models
            assertEquals(listOf("high", "low", "future"), models[0].reasoningEfforts)
            assertEquals(emptyList<String>(), models[1].reasoningEfforts)
            assertNull(models[2].reasoningEfforts)
            assertEquals(listOf("low", "high"), models[3].reasoningEfforts)
        }
    }
}
