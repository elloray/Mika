package com.wangzan101.mika.core.search

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class SearchHttpLimitTest {
    private val limit = 1_000_000
    private val hit = WebSearchHit("标题", "https://example.com/", "摘要")

    @Test fun rejectsOversizedChunkedResponseBeforeTheServerFinishes() =
        withResponse(json(limit + 1).toByteArray(), chunked = true, holdOpen = true) { search ->
            assertTooLarge(search.search("测试", 1))
        }

    @Test fun rejectsOversizedGzipResponseBeforeTheServerFinishes() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(json(limit + 1).toByteArray()) }
        }.toByteArray()
        assertTrue(compressed.size < limit)
        withResponse(compressed, chunked = true, holdOpen = true, gzip = true) { search ->
            assertTooLarge(search.search("测试", 1))
        }
    }

    @Test fun acceptsUtf8ResponseAtTheCharacterLimit() =
        withResponse(json(limit).toByteArray()) { search ->
            assertEquals(WebSearchResult.Success(listOf(hit)), search.search("测试", 1))
        }

    @Test fun acceptsUtf16BomResponseAtTheCharacterLimit() =
        withResponse(json(limit).toByteArray(Charsets.UTF_16)) { search ->
            assertEquals(WebSearchResult.Success(listOf(hit)), search.search("测试", 1))
        }

    @Test fun rejectsResponseOneCharacterOverTheLimit() =
        withResponse(json(limit + 1).toByteArray()) { search ->
            assertTooLarge(search.search("测试", 1))
        }

    private fun json(length: Int): String {
        val prefix = """{"results":[{"title":"标题","url":"https://example.com/","content":"摘要"}],"padding":""""
        val suffix = "\"}"
        return prefix + "中".repeat(length - prefix.length - suffix.length) + suffix
    }

    private fun assertTooLarge(result: WebSearchResult) {
        assertEquals(
            WebSearchResult.Failure("invalid_response", "The search service response is too large."),
            result,
        )
    }

    private fun withResponse(
        body: ByteArray,
        chunked: Boolean = false,
        holdOpen: Boolean = false,
        gzip: Boolean = false,
        block: suspend (SearxngSearch) -> Unit,
    ) = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        server.createContext("/search") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                if (gzip) exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, if (chunked) 0 else body.size.toLong())
                exchange.responseBody.write(body)
                exchange.responseBody.flush()
                // 保持 HTTP 正文未结束，客户端应当在超限时自行返回。
                if (holdOpen) check(release.await(10, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block(SearxngSearch("http://127.0.0.1:${server.address.port}/".toHttpUrl(), client))
        } finally {
            release.countDown()
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
