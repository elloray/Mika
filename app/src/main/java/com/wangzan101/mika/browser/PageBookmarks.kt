package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 存下的一页书签:网址、标题(可以为空,显示时用网站名)、加入的时间;[edited] 是用户自己改过标题。 */
data class PageBookmark(val url: String, val title: String, val addedAtMs: Long, val edited: Boolean = false)

/**
 * 浏览器页的书签:用户自己保存的具体网页,最近加入的在前,同一网址(不计 # 后面)只留一条,至多 [limit] 条。
 * 只收 http/https 网页。存在 App 私有目录的一个 JSON 文件里;文件坏了就从空表重新开始。
 */
class PageBookmarks(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** 最近加入的在前。 */
    val entries: StateFlow<List<PageBookmark>> = _entries.asStateFlow()

    /** 加入书签;网址不是 http/https 网页时为 false。同一网址再存一次会挪到最前、标题换成新的。 */
    @Synchronized
    fun add(url: String, title: String?): Boolean {
        val u = clean(url) ?: return false
        val key = key(u)
        val page = PageBookmark(u, title?.trim().orEmpty(), clock())
        save((listOf(page) + _entries.value.filter { key(it.url) != key }).take(limit))
        return true
    }

    /** 当前页标题后到时补上:只改已有的那一条,不挪位置;用户自己改过标题的不动。 */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && !it.edited && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
    }

    /** 用户自己改标题;空标题不生效。改过之后页面标题再变也不覆盖。 */
    @Synchronized
    fun rename(url: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val key = key(url)
        if (_entries.value.none { key(it.url) == key }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t, edited = true) else it })
    }

    /** 删掉一条;没有这一条时为 false。 */
    @Synchronized
    fun remove(url: String): Boolean {
        val key = key(url)
        val rest = _entries.value.filter { key(it.url) != key }
        if (rest.size == _entries.value.size) return false
        save(rest)
        return true
    }

    fun contains(url: String): Boolean = _entries.value.any { key(it.url) == key(url) }

    @Synchronized
    fun clear() = save(emptyList())

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun clean(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val rest = u.substringAfter("://", "")
        return u.takeIf { rest.isNotEmpty() && !rest.startsWith("/") && !rest.startsWith("?") && !rest.startsWith("#") }
    }

    private fun load(): List<PageBookmark> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::clean) ?: return@mapNotNull null
                PageBookmark(
                    url,
                    (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    (o["edited"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }.orEmpty().distinctBy { key(it.url) }.take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<PageBookmark>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.addedAtMs); put("edited", it.edited) } }).toString()
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        const val LIMIT = 300
        private const val FILE = "browser_bookmarks.json"
    }
}
