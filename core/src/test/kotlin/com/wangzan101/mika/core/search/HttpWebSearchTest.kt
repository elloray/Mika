package com.wangzan101.mika.core.search

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpWebSearchTest {
    private fun response(body: String = """{"results":[{"title":"标题","url":"https://example.com/a","snippet":"摘要"}]}""") =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun keylessRequestHasQueryAndNoAuthorization() = runTest {
        val server = MockWebServer()
        server.enqueue(response())
        server.start()
        try {
            val result = HttpWebSearch(server.url("/search").toString(), client = OkHttpClient()).search("安卓  generic", 3)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/search?query=%E5%AE%89%E5%8D%93%20%20generic&count=3", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(listOf(WebSearchHit("标题", "https://example.com/a", "摘要")), (result as WebSearchResult.Success).hits)
        } finally { server.shutdown() }
    }

    @Test fun keyUsesBearerAuthenticationAndCountIsCapped() = runTest {
        val server = MockWebServer()
        server.enqueue(response())
        server.start()
        try {
            HttpWebSearch(server.url("/search").toString(), " secret ", OkHttpClient()).search("query", 500)
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("10", request.requestUrl?.queryParameter("count"))
        } finally { server.shutdown() }
    }

    @Test fun rateLimitReportsRetryAfterWithoutRetrying() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17").setBody("{}"))
        server.start()
        try {
            val result = HttpWebSearch(server.url("/search").toString(), client = OkHttpClient()).search("query", 5)
            assertEquals(WebSearchResult.Failure("rate_limited", "The search endpoint is rate limiting requests; retry later or use the browser.", 17), result)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun paymentRequiredMapsToQuotaFailure() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(402).setBody("{}"))
        server.start()
        try {
            val result = HttpWebSearch(server.url("/search").toString(), client = OkHttpClient()).search("query", 5)
            assertEquals("quota_exhausted", (result as WebSearchResult.Failure).code)
        } finally { server.shutdown() }
    }

    @Test fun unreachableEndpointIsOfflineAndMalformedJsonIsRejected() = runTest {
        val server = MockWebServer()
        server.start()
        val endpoint = server.url("/search").toString()
        server.shutdown()
        val offline = HttpWebSearch(endpoint, client = OkHttpClient()).search("query", 5)
        assertEquals("offline", (offline as WebSearchResult.Failure).code)

        val malformed = MockWebServer()
        malformed.enqueue(response("not json"))
        malformed.start()
        try {
            val result = HttpWebSearch(malformed.url("/search").toString(), client = OkHttpClient()).search("query", 5)
            assertEquals("invalid_response", (result as WebSearchResult.Failure).code)
        } finally { malformed.shutdown() }
    }

    @Test fun endpointCannotExceedTheRequestedResultCount() = runTest {
        val server = MockWebServer()
        server.enqueue(response("""{"results":[
            {"title":"一","url":"https://example.com/1","snippet":"1"},
            {"title":"二","url":"https://example.com/2","snippet":"2"},
            {"title":"三","url":"https://example.com/3","snippet":"3"}
        ]}"""))
        server.start()
        try {
            val result = HttpWebSearch(server.url("/search").toString(), client = OkHttpClient()).search("query", 2)
            assertEquals(listOf("https://example.com/1", "https://example.com/2"),
                (result as WebSearchResult.Success).hits.map { it.url })
        } finally { server.shutdown() }
    }

    @Test fun endpointValidationDoesNotProvideADefaultService() {
        assertNull(HttpWebSearch.parseEndpoint(""))
        assertNull(HttpWebSearch.parseEndpoint("ftp://example.com/search"))
        assertNull(HttpWebSearch.parseEndpoint("https://user:pass@example.com/search"))
        assertNull(HttpWebSearch.parseEndpoint("https://:pass@example.com/search"))
        assertNull(HttpWebSearch.parseEndpoint("https://example.com/search?key=value"))
        assertTrue(HttpWebSearch.parseEndpoint("http://127.0.0.1:1234/search") != null)
    }
}
