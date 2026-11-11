package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * You.com 网页搜索。没有密钥时走免费的 `GET https://api.you.com/v1/agents/search?query=&count=`(不用账号,每天 100 次);
 * 有密钥时走 `POST https://ydc-index.io/v1/search`,带 X-API-Key。两边返回同一种 `{"results":{"web":[…]}}`,
 * 摘要取 description,没有时取第一段 snippets。一条都没搜到时接口照样回 200,但 `results` 里没有 `web`(`{"results":{}}`),
 * 这是「没有结果」,不是出错。
 */
class YouSearch(
    apiKey: String? = null,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val keylessUrl: HttpUrl = KEYLESS_URL.toHttpUrl(),
    private val keyedUrl: HttpUrl = KEYED_URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey?.trim()?.takeIf(String::isNotEmpty)

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val request = if (key == null) {
            Request.Builder().url(keylessUrl.newBuilder().addQueryParameter("query", query).addQueryParameter("count", n.toString()).build()).get()
        } else {
            Request.Builder().url(keyedUrl).header("X-API-Key", key)
                .post(SearchHttp.json(buildJsonObject { put("query", query); put("count", n) }))
        }.build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonObject
            ?: return WebSearchResult.Failure("invalid_response", "The search service response has no results object.")
        val web = when (val w = results["web"]) {
            null, JsonNull -> return WebSearchResult.Success(emptyList())
            is JsonArray -> w
            else -> return WebSearchResult.Failure("invalid_response", "The search service response has a results.web that is not an array.")
        }
        return WebSearchResult.Success(web.take(count).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val snippets = (o["snippets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val snippet = SearchHttp.text(o, "description").ifBlank { snippets.firstOrNull().orEmpty() }
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), snippet)
        })
    }

    companion object {
        const val KEYLESS_URL = "https://api.you.com/v1/agents/search"
        const val KEYED_URL = "https://ydc-index.io/v1/search"
    }
}
