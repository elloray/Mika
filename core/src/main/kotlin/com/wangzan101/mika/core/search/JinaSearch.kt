package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Jina 搜索:`POST https://s.jina.ai/`,请求体 `{"q","num"}`,`Authorization: Bearer`;
 * 带 `X-Respond-With: no-content` 只要标题、网址和摘要,不把每页正文拉回来。结果在 `data[]`,摘要取 description,没有时取 content。
 * 一条都没搜到时 Jina 回的是 422(实测:`{"code":422,"status":42206,"message":"No search results available for query …"}`),
 * 这是「没有结果」,不是出错;别的 422 照旧当出错。
 */
class JinaSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("X-Respond-With", "no-content")
            .post(SearchHttp.json(buildJsonObject { put("q", query); put("num", n) })).build()
        return SearchHttp.call(client, request, special = ::noResults) { root -> parse(root, n) }
    }

    private fun noResults(response: Response): WebSearchResult? {
        if (response.code != 422) return null
        val body = runCatching { Json.parseToJsonElement(response.peekBody(MAX_ERROR_BODY).string()) as? JsonObject }.getOrNull() ?: return null
        val status = (body["status"] as? JsonPrimitive)?.intOrNull
        val message = (body["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (status == NO_RESULTS_STATUS || message.startsWith("No search results", ignoreCase = true)) WebSearchResult.Success(emptyList()) else null
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val data = root["data"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The Jina search response has no data array.")
        return WebSearchResult.Success(data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "description").ifBlank { SearchHttp.text(o, "content") })
        }.take(count))
    }

    companion object {
        const val URL = "https://s.jina.ai/"
        /** Jina 零命中时错误体里的 status。 */
        private const val NO_RESULTS_STATUS = 42206
        private const val MAX_ERROR_BODY = 16_384L
    }
}
