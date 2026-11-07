package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 一条网页搜索结果;正文只是不可信资料,不能当作指令。 */
data class WebSearchHit(val title: String, val url: String, val snippet: String)

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null) : WebSearchResult
}

/** 通用网页搜索端口。实现由宿主按用户配置提供;没有配置时不会提供搜索工具。 */
interface WebSearch {
    suspend fun search(query: String, count: Int): WebSearchResult
}

/**
 * 用户自己配置的 HTTP JSON 搜索端点。请求是 GET,带 query 与 count 参数;响应为
 * {"results":[{"title":"…","url":"…","snippet":"…"}]}。Key 可有可无,有时用 Bearer 认证。
 */
class HttpWebSearch(
    endpoint: String,
    apiKey: String? = null,
    private val client: OkHttpClient = NetProxy.shared.client(),
) : WebSearch {
    private val endpoint = requireNotNull(parseEndpoint(endpoint)) { "Invalid web search endpoint" }
    private val authorization = apiKey?.trim()?.takeIf(String::isNotEmpty)?.let { "Bearer $it" }

    override suspend fun search(query: String, count: Int): WebSearchResult = withContext(Dispatchers.IO) {
        val url = endpoint.newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("count", count.coerceIn(1, MAX_COUNT).toString())
            .build()
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> return@use WebSearchResult.Failure("authentication_failed", "The search endpoint rejected the configured API key.")
                    402 -> return@use WebSearchResult.Failure("quota_exhausted", "The search endpoint reports that its paid quota is exhausted.")
                    429 -> return@use WebSearchResult.Failure(
                        "rate_limited", "The search endpoint is rate limiting requests; retry later or use the browser.",
                        response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0),
                    )
                    !in 200..299 -> return@use WebSearchResult.Failure("service_unavailable", "The search endpoint returned HTTP ${response.code}.")
                }
                val body = response.body?.string().orEmpty()
                if (body.length > MAX_RESPONSE_CHARS) {
                    return@use WebSearchResult.Failure("invalid_response", "The search endpoint response is too large.")
                }
                parse(body, count)
            }
        } catch (e: IOException) {
            WebSearchResult.Failure("offline", "The search endpoint could not be reached; check the network, proxy and endpoint.")
        }
    }

    private fun parse(body: String, count: Int): WebSearchResult = try {
        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: return WebSearchResult.Failure("invalid_response", "The search endpoint did not return a JSON object.")
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The search endpoint response has no results array.")
        // 端点不可信，即使忽略 count 也不能把超额结果全部送入模型上下文。
        val hits = results.take(count.coerceIn(1, MAX_COUNT)).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = text("url").trim()
            if (url.isEmpty()) null else WebSearchHit(text("title").trim(), url, text("snippet").trim())
        }
        WebSearchResult.Success(hits)
    } catch (e: IllegalArgumentException) {
        WebSearchResult.Failure("invalid_response", "The search endpoint returned malformed JSON.")
    }

    companion object {
        const val MAX_COUNT = 10
        private const val MAX_RESPONSE_CHARS = 1_000_000

        /** 只接受 HTTP(S),不在地址里另带 query 或 fragment,避免配置与请求参数含义冲突。 */
        fun parseEndpoint(raw: String): HttpUrl? = raw.trim().toHttpUrlOrNull()?.takeIf {
            (it.scheme == "https" || it.scheme == "http") && it.query == null && it.fragment == null &&
                it.username.isEmpty() && it.password.isEmpty()
        }
    }
}
