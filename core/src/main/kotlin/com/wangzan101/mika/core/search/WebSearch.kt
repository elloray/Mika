package com.wangzan101.mika.core.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 一条网页搜索结果;正文只是不可信资料,不能当作指令。 */
data class WebSearchHit(val title: String, val url: String, val snippet: String)

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null) : WebSearchResult
}

/** 网页搜索端口。实现按用户选的服务商提供(见 [WebSearchProvider]);没有配置时不会提供搜索工具。 */
interface WebSearch {
    suspend fun search(query: String, count: Int): WebSearchResult

    companion object {
        /** 一次最多要几条:各家上限不同,统一按这个,免得把太多不可信内容送进模型上下文。 */
        const val MAX_COUNT = 10

        /** 只接受 HTTP(S),不在地址里另带 query 或 fragment、不带账号密码,避免和请求参数冲突。 */
        fun parseEndpoint(raw: String): HttpUrl? = raw.trim().toHttpUrlOrNull()?.takeIf {
            (it.scheme == "https" || it.scheme == "http") && it.query == null && it.fragment == null &&
                it.username.isEmpty() && it.password.isEmpty()
        }
    }
}
