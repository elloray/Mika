package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** 一条命中。[inTitle] 表示标题里有词;片段取自正文,没有合适的正文片段时为 null。 */
data class ConversationHit(val id: String, val inTitle: Boolean, val snippet: String?, val score: Int)

/** 一次正文搜索的结果;[truncated] 为真表示还有更多命中没返回。 */
data class SearchResult(val hits: List<ConversationHit>, val truncated: Boolean = false)

/**
 * 侧栏搜索:查对话标题和对话里看得见的文字(用户原话、插话、助手的回答)。
 *
 * 正文按对话文件的修改时间缓存:文件没变就不再解析。读文件走 [turnsOf](就是 [ConversationStore] 的解析),
 * 不拿 store 的锁,后台任务收尾往对话里补写时不会被搜索挡住。
 */
class ConversationSearch(
    private val fileOf: (String) -> File,
    private val turnsOf: (File) -> List<Turn>?,
) {
    /** 缓存的一条正文。[updatedAt] 是 [ConversationSummary.updatedAt],变了就重读。 */
    private data class Entry(val updatedAt: Long, val body: Body)

    /** 一个对话里看得见的文字,按出现顺序;片段从其中一段里取。 */
    private data class Body(val segments: List<String>, val firstUser: String?)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()

    /**
     * 只查标题(纯内存),输入时立刻显示。所有词都在标题里才算命中;
     * 有一个词只在正文里也先不显示,等 [search] 扫完正文再说。
     */
    fun titleMatches(query: String, candidates: List<ConversationSummary>): List<ConversationHit> {
        val terms = termsOf(query)
        if (terms.isEmpty()) return emptyList()
        val hits = candidates.mapNotNull { c ->
            val inTitle = terms.map { c.title.contains(it, ignoreCase = true) }
            if (inTitle.any { !it }) return@mapNotNull null
            ConversationHit(
                c.id, inTitle = true, snippet = null,
                score = score(titleAll = true, bodyAll = false, titleCount = terms.size, titlePrefix = prefix(c.title, terms.first())),
            )
        }
        return ranked(hits, candidates).take(MAX_HITS)
    }

    /** 查标题和正文。在 IO 线程调用;读完每个对话检查一次取消,输入变了就早点停。 */
    suspend fun search(query: String, candidates: List<ConversationSummary>): SearchResult {
        val terms = termsOf(query)
        if (terms.isEmpty()) return SearchResult(emptyList())
        val hits = ArrayList<ConversationHit>(candidates.size)
        val alive = HashSet<String>(candidates.size)
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            alive += c.id
            val body = if (c.updatedAt == 0L) Body(emptyList(), null) else body(c)
            val inTitle = terms.map { c.title.contains(it, ignoreCase = true) }
            val inBody = terms.map { t -> body.segments.any { it.contains(t, ignoreCase = true) } }
            // 每个词至少出现在标题或正文的一处
            if (inTitle.indices.any { !inTitle[it] && !inBody[it] }) continue
            val titleAll = inTitle.all { it }
            val best = bestSegment(body.segments, terms)
            // 标题就是第一句话、而且标题已经命中时,不再把同一句话当片段重复一遍
            val snippet = best?.takeIf { !(titleAll && body.firstUser != null && it.text == body.firstUser) }?.let(::window)
            hits += ConversationHit(
                c.id, inTitle = inTitle.any { it }, snippet = snippet,
                score = score(titleAll, inBody.all { it }, inTitle.count { it }, prefix(c.title, terms.first())),
            )
        }
        synchronized(lock) { cache.keys.retainAll(alive) }
        val sorted = ranked(hits, candidates)
        return SearchResult(sorted.take(MAX_HITS), truncated = sorted.size > MAX_HITS)
    }

    /** 清空正文缓存;侧栏关掉时调用,免得一次搜索过后一直占着所有对话的正文。 */
    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private fun body(c: ConversationSummary): Body {
        val cached = synchronized(lock) { cache[c.id] }
        if (cached != null && cached.updatedAt == c.updatedAt) return cached.body
        val turns = runCatching { turnsOf(fileOf(c.id)) }.getOrNull().orEmpty()
        val segments = ArrayList<String>()
        var firstUser: String? = null
        for (t in turns) {
            if (firstUser == null) firstUser = t.userText.takeIf(String::isNotBlank)
            segments += visibleText(t)
        }
        val body = Body(segments, firstUser)
        synchronized(lock) { cache[c.id] = Entry(c.updatedAt, body) }
        return body
    }

    /** 一轮里用户和助手看得见的字;同一轮里重复的(结局和结果常常一样)只留一份。 */
    private fun visibleText(t: Turn): List<String> = buildList {
        fun put(s: String?) {
            s?.takeIf(String::isNotBlank)?.let { add(it) }
        }
        put(t.userText)
        t.steers.forEach(::put)
        t.finishedReplies.forEach(::put)
        t.interruptedReplies.forEach(::put)
        put(t.outcome)
        put(t.result)
        put(t.note)
    }.distinct()

    /** 命中词最多的一段;一样多时取命中位置靠前的。 */
    private data class Best(val text: String, val index: Int, val length: Int)

    private fun bestSegment(segments: List<String>, terms: List<String>): Best? {
        var best: Best? = null
        var bestCount = 0
        for (seg in segments) {
            var count = 0
            var first = -1
            var firstLength = 0
            for (term in terms) {
                val i = seg.indexOf(term, ignoreCase = true)
                if (i < 0) continue
                count++
                if (first < 0 || i < first) {
                    first = i
                    firstLength = term.length
                }
            }
            if (count == 0) continue
            if (count > bestCount || (count == bestCount && (best == null || first < best.index))) {
                best = Best(seg, first, firstLength)
                bestCount = count
            }
        }
        return best
    }

    /** 命中处前后各留一段,换行压成空格;截断处加省略号。 */
    private fun window(best: Best): String {
        val text = best.text
        val start = (best.index - SNIPPET_BEFORE).coerceAtLeast(0)
        val end = (best.index + best.length + SNIPPET_AFTER).coerceAtMost(text.length)
        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        val piece = text.substring(safeStart(text, start), safeEnd(text, end)).replace(SPACE, " ").trim()
        return head + piece + tail
    }

    /** 别把 emoji 的代理对截成半个:起点落在低位代理上时往前挪一位。 */
    private fun safeStart(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
        return i
    }

    /** 终点落在低位代理上时往后挪一位,把这一对留全。 */
    private fun safeEnd(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i++
        return i
    }

    private fun score(titleAll: Boolean, bodyAll: Boolean, titleCount: Int, titlePrefix: Boolean): Int =
        (if (titleAll) 100 else if (titleCount > 0) 40 else 0) +
            (if (bodyAll) 30 else 0) +
            titleCount * 5 +
            (if (titlePrefix) 15 else 0)

    private fun prefix(title: String, term: String): Boolean = title.startsWith(term, ignoreCase = true)

    /** 分高的在前;同分时置顶的在前,再按最近改过的排。 */
    private fun ranked(hits: List<ConversationHit>, candidates: List<ConversationSummary>): List<ConversationHit> {
        val byId = candidates.associateBy { it.id }
        return hits.sortedWith(
            compareByDescending<ConversationHit> { it.score }
                .thenByDescending { byId[it.id]?.pinnedAt != null }
                .thenByDescending { byId[it.id]?.updatedAt ?: 0L }
                .thenBy { it.id },
        )
    }

    companion object {
        /** 一次最多返回多少条;再多就只显示前这么多。 */
        const val MAX_HITS = 100

        private const val MAX_TERMS = 8
        private const val SNIPPET_BEFORE = 24
        private const val SNIPPET_AFTER = 48
        // Java 的 \s 只认 ASCII 空白,中文输入法的全角空格和粘贴来的 NBSP 也要算分隔
        private val SPACE = Regex("[\\s\\u3000\\u00A0]+")

        /** 查询切成关键词:空白分隔,空的和重复的不算,最多 8 个。 */
        fun termsOf(query: String): List<String> =
            query.trim().split(SPACE).filter(String::isNotEmpty).distinct().take(MAX_TERMS)
    }
}
