package com.wangzan101.mika.core.self

/**
 * 用户浏览器能代开的地址:只 http/https、不要账号密码、长度有限。
 * 核心不引用网页能力(依赖方向:能力 → 核心),所以这里只做基本检查;
 * App 侧打开时 [com.wangzan101.mika.core.self.SelfPort] 的实现还会经浏览器自己的 WebUrlPolicy 再查一遍。
 */
object SelfUrlPolicy {
    const val MAX_URL = 2048

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** null = 可以;否则是给模型看的原因。 */
    fun check(url: String): String? {
        val u = url.trim()
        if (u.length > MAX_URL) return "the URL is longer than $MAX_URL characters"
        if (u.isEmpty()) return "the URL is empty"
        if (u.any { it.isWhitespace() || it.isISOControl() }) return "the URL must not contain spaces or control characters"
        val scheme = SCHEME.find(u)?.groupValues?.get(1)?.lowercase()
            ?: return "the URL must start with http:// or https://"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        val authority = u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "the URL has no domain"
        if ('@' in authority) return "URLs with a username or password are not allowed"
        return null
    }

    /** 主机名(小写、去掉开头的 www.);解析不出为 null。 */
    fun host(url: String): String? = runCatching {
        val authority = url.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority) return@runCatching null
        val h = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        h.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 用户给的主机名或网址 → 主机名。 */
    fun hostOf(input: String): String? {
        val s = input.trim()
        return host(if ("://" in s) s else "https://$s")
    }
}
