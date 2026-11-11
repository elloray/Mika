package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 自建的 SearXNG 实例:`GET {实例地址}/search?q=&format=json`,结果在 `results[]`,摘要取 content。
 * 实例默认不开 JSON 输出,没开时回 403,要在 settings.yml 的 `search.formats` 里加上 json;这和密钥无关,单独说明。
 * SearXNG 不按条数返回,这里只取前 count 条。
 */
class SearxngSearch(
    private val base: HttpUrl,
    private val client: OkHttpClient = NetProxy.shared.client(),
) : WebSearch {
    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        // 填的是实例地址;已经写到 /search 的也认
        val path = if (base.pathSegments.lastOrNull() == "search") base.newBuilder() else base.newBuilder().addPathSegment("search")
        val url = path.addQueryParameter("q", query).addQueryParameter("format", "json").build()
        return SearchHttp.call(client, Request.Builder().url(url).get().build(), special = { response ->
            if (response.code == 403) WebSearchResult.Failure(
                "json_disabled",
                "The SearXNG instance refused JSON output (HTTP 403); its owner must add json to search.formats in settings.yml. Use the browser meanwhile.",
            ) else null
        }) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The SearXNG response has no results array.")
        return WebSearchResult.Success(results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "content"))
        }.take(count))
    }
}
