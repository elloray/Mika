package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * 一个搜索引擎:起始页和地址栏显示名字([displayName]),搜索词换成搜索网址([searchUrl]),
 * 再从网址读回搜索词([queryOf],用来在「最近打开」和地址栏里显示搜的词)。
 * 内置的几个是 [SearchEngine],用户自己加的是 [CustomSearchEngine]。
 */
sealed interface SearchEngineSpec {
    val id: String
    val displayName: String

    fun searchUrl(query: String): String

    /** 网址是这个引擎的搜索结果页时为搜的词,否则为 null。 */
    fun queryOf(url: String): String?

    companion object {
        /** 网址是 [engines] 里哪个引擎的搜索结果页、搜的是什么;都不是为 null。只解码一次。 */
        fun match(url: String, engines: List<SearchEngineSpec>): Pair<SearchEngineSpec, String>? =
            engines.firstNotNullOfOrNull { e -> e.queryOf(url)?.let { e to it } }

        /** 网址是 [engines] 里哪个引擎的搜索结果页;都不是为 null。 */
        fun of(url: String, engines: List<SearchEngineSpec>): SearchEngineSpec? = match(url, engines)?.first
    }
}

/**
 * 内置的搜索引擎。每个只是一个前缀:搜索网址是前缀加编码后的词,反过来从网址认出搜的是什么。
 * 名字按界面语言取:品牌名多数语言照写,简中写「必应」「谷歌」「百度」。
 */
enum class SearchEngine(override val id: String, private val nameRes: Int, private val prefix: String) : SearchEngineSpec {
    Bing("bing", R.string.browser_engine_bing, "https://www.bing.com/search?q="),
    Google("google", R.string.browser_engine_google, "https://www.google.com/search?q="),
    DuckDuckGo("duckduckgo", R.string.browser_engine_duckduckgo, "https://duckduckgo.com/?q="),
    Baidu("baidu", R.string.browser_engine_baidu, "https://www.baidu.com/s?wd=");

    override val displayName: String get() = tr(nameRes)

    override fun searchUrl(query: String): String = prefix + URLEncoder.encode(query.trim(), "UTF-8")

    override fun queryOf(url: String): String? {
        if (!url.startsWith(prefix)) return null
        val q = url.removePrefix(prefix).substringBefore('&').substringBefore('#')
        return decode(q)
    }

    companion object {
        fun fromId(id: String?): SearchEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 用户自己加的搜索引擎:一个网址模板,用 [PLACEHOLDER] 代表搜索词,例如 `https://example.com/search?q=%s`。
 * 模板必须正好一个占位符;生出的网址以模板前半段开头,后面(有的话)是模板后半段。
 * 从网址读回搜索词时,后半段为空就取到 `&` 或 `#` 为止,否则取到后半段出现的位置为止。
 */
@ConsistentCopyVisibility
data class CustomSearchEngine private constructor(
    override val id: String,
    private val name: String,
    val template: String,
) : SearchEngineSpec {
    override val displayName: String get() = name

    private val prefix = template.substringBefore(PLACEHOLDER)
    private val suffix = template.substringAfter(PLACEHOLDER, "")

    override fun searchUrl(query: String): String =
        template.replace(PLACEHOLDER, URLEncoder.encode(query.trim(), "UTF-8"))

    override fun queryOf(url: String): String? {
        if (!url.startsWith(prefix)) return null
        val rest = url.removePrefix(prefix)
        val raw = if (suffix.isEmpty()) {
            rest.substringBefore('&').substringBefore('#')
        } else {
            rest.substring(0, suffixStart(rest) ?: return null)
        }
        return decode(raw)
    }

    /** 模板后半段出现的位置:后面得是结尾、`&` 或 `#`,免得搜索词里正好也有这一段时截错。 */
    private fun suffixStart(rest: String): Int? {
        var from = 0
        while (true) {
            val i = rest.indexOf(suffix, from)
            if (i < 0) return null
            val after = i + suffix.length
            if (after == rest.length || rest[after] == '&' || rest[after] == '#') return i
            from = i + 1
        }
    }

    companion object {
        const val PLACEHOLDER = "%s"

        /** 按用户输入的名字和网址建一个;名字为空、网址不是 http(s) 或不是正好一个占位符时为 null。 */
        fun create(name: String, template: String): CustomSearchEngine? =
            create("custom:" + UUID.randomUUID(), name, template)

        /** 从存下的内容还原;不合法(旧版本、文件被改坏)时为 null。 */
        internal fun create(id: String, name: String, template: String): CustomSearchEngine? {
            val n = name.trim()
            val t = template.trim()
            if (n.isEmpty() || id.isBlank() || !valid(t)) return null
            return CustomSearchEngine(id, n, t)
        }

        private fun valid(template: String): Boolean {
            if (Regex(PLACEHOLDER).findAll(template).count() != 1) return false
            if (!template.startsWith("http://") && !template.startsWith("https://")) return false
            if (template.any { it.isWhitespace() }) return false
            val hostStart = template.indexOf("://") + 3
            val authorityEnd = listOf(template.indexOf('/', hostStart), template.indexOf('?', hostStart), template.indexOf('#', hostStart))
                .filter { it >= 0 }.minOrNull() ?: template.length
            if (template.indexOf(PLACEHOLDER) < authorityEnd) return false
            return runCatching { URI(template.replace(PLACEHOLDER, "mika")).rawAuthority?.isNotBlank() == true }.getOrDefault(false)
        }
    }
}

private fun decode(raw: String): String? =
    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
