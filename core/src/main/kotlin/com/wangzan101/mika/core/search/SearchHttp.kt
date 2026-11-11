package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.awaitBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 各家服务商共用的那一段:发请求、把常见的 HTTP 状态换成给模型的失败、读 JSON。
 * 各家特殊的状态由 [special] 先认:可以是一种失败(比如 SearXNG 的 403),也可以是结果(比如 Jina 零命中回的 422)。
 */
internal object SearchHttp {
    private const val MAX_RESPONSE_CHARS = 1_000_000
    /** 一条摘要最多这么长:端点不可信,不能把整页正文塞进模型上下文。 */
    private const val MAX_SNIPPET_CHARS = 600
    private val JSON_TYPE = "application/json".toMediaType()
    private const val USER_AGENT = "Mika (+https://github.com/wangzan101/Mika)"

    fun json(body: JsonObject): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    suspend fun call(
        client: OkHttpClient,
        request: Request,
        special: (Response) -> WebSearchResult? = { null },
        parse: (JsonObject) -> WebSearchResult,
    ): WebSearchResult {
        val r = request.newBuilder().header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(r).awaitBody { response ->
                special(response)?.let { return@awaitBody it }
                failure(response)?.let { return@awaitBody it }
                val body = response.body?.charStream()?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        // 多读一个字符判定超限，避免等待超大正文全部下载完成。
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - text.length + 1))
                        if (count == -1) break
                        if (text.length + count > MAX_RESPONSE_CHARS) {
                            return@awaitBody WebSearchResult.Failure("invalid_response", "The search service response is too large.")
                        }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty()
                val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: IllegalArgumentException) { null }
                    ?: return@awaitBody WebSearchResult.Failure("invalid_response", "The search service did not return a JSON object.")
                parse(root)
            }
        } catch (e: IOException) {
            WebSearchResult.Failure("offline", "The search service could not be reached; check the network and proxy.")
        }
    }

    private fun failure(response: Response): WebSearchResult.Failure? = when (response.code) {
        in 200..299 -> null
        401, 403 -> WebSearchResult.Failure("authentication_failed", "The search service rejected the configured API key.")
        402 -> WebSearchResult.Failure("quota_exhausted", "The search service reports that its quota or credits are exhausted.")
        429 -> WebSearchResult.Failure(
            "rate_limited", "The search service is rate limiting requests; retry later or use the browser.",
            response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0),
        )
        else -> WebSearchResult.Failure("service_unavailable", "The search service returned HTTP ${response.code}.")
    }

    fun text(o: JsonObject, name: String): String = (o[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    /** 没有网址的结果不要;摘要截短。 */
    fun hit(title: String, url: String, snippet: String): WebSearchHit? =
        if (url.isBlank()) null else WebSearchHit(title, url, snippet.trim().let { if (it.length > MAX_SNIPPET_CHARS) it.take(MAX_SNIPPET_CHARS) + "…" else it })
}
