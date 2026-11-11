package com.wangzan101.mika.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouSearchTest {
    private val body = """{"results":{"web":[
        {"url":"https://example.com/a","title":"标题","description":"摘要","snippets":["第一段"]},
        {"url":"https://example.com/b","title":"B","description":"","snippets":["只有片段"]},
        {"title":"没有网址"}
    ]},"metadata":{}}"""

    private fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) = runTest {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try { block(server) } finally { server.shutdown() }
    }

    private fun ok(b: String = body) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(b)

    private fun you(server: MockWebServer, key: String? = null) =
        YouSearch(key, OkHttpClient(), keylessUrl = server.url("/v1/agents/search"), keyedUrl = server.url("/v1/search"))

    @Test fun keylessIsAGetWithQueryAndCount() = withServer(ok()) { server ->
        val result = you(server).search("安卓 agent", 3)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/agents/search?query=%E5%AE%89%E5%8D%93%20agent&count=3", request.path)
        assertNull(request.getHeader("X-API-Key"))
        assertNull(request.getHeader("Authorization"))
        assertEquals(
            listOf(WebSearchHit("标题", "https://example.com/a", "摘要"), WebSearchHit("B", "https://example.com/b", "只有片段")),
            (result as WebSearchResult.Success).hits,
        )
    }

    @Test fun keyIsAPostWithXApiKeyAndCountIsCapped() = withServer(ok()) { server ->
        you(server, " secret ").search("query", 500)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/search", request.path)
        assertEquals("secret", request.getHeader("X-API-Key"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("query", sent["query"]!!.jsonPrimitive.content)
        assertEquals(WebSearch.MAX_COUNT, sent["count"]!!.jsonPrimitive.int)
    }

    @Test fun resultsBeyondCountAreDropped() = withServer(ok()) { server ->
        assertEquals(1, (you(server).search("q", 1) as WebSearchResult.Success).hits.size)
    }

    @Test fun longSnippetIsShortened() = withServer(ok("""{"results":{"web":[{"url":"https://e.com","title":"t","description":"${"字".repeat(2000)}"}]}}""")) { server ->
        val snippet = (you(server).search("q", 1) as WebSearchResult.Success).hits.single().snippet
        assertTrue(snippet.length < 700)
        assertTrue(snippet.endsWith("…"))
    }

    @Test fun wrongShapeIsInvalidResponse() = withServer(ok("""{"results":[]}""")) { server ->
        assertEquals("invalid_response", (you(server).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun noHitsIsAnEmptySuccessNotAFailure() = withServer(ok("""{"results":{},"metadata":{"query":"q"}}""")) { server ->
        // 免费接口零命中时的真实返回:results 里没有 web
        assertEquals(WebSearchResult.Success(emptyList()), you(server).search("q", 5))
    }

    @Test fun nullWebIsAlsoNoHits() = withServer(ok("""{"results":{"web":null}}""")) { server ->
        assertEquals(WebSearchResult.Success(emptyList()), you(server).search("q", 5))
    }

    @Test fun webThatIsNotAListIsInvalid() = withServer(ok("""{"results":{"web":{}}}""")) { server ->
        assertEquals("invalid_response", (you(server).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun missingResultsIsInvalid() = withServer(ok("""{"metadata":{}}""")) { server ->
        assertEquals("invalid_response", (you(server).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun forbiddenKeyIsAuthenticationFailure() = withServer(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")) { server ->
        assertEquals("authentication_failed", (you(server, "bad").search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun rateLimitCarriesRetryAfter() = withServer(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}")) { server ->
        val failure = you(server).search("q", 5) as WebSearchResult.Failure
        assertEquals("rate_limited", failure.code)
        assertEquals(30L, failure.retryAfterSeconds)
    }

    @Test fun quotaIsReported() = withServer(MockResponse().setResponseCode(402).setBody("{}")) { server ->
        assertEquals("quota_exhausted", (you(server).search("q", 5) as WebSearchResult.Failure).code)
    }
}
