package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun summary(id: String, title: String, updatedAt: Long = 0L, pinnedAt: Long? = null) =
        ConversationSummary(id, title, updatedAt, pinnedAt)

    private fun hits(store: ConversationStore, query: String, candidates: List<ConversationSummary> = store.list()) =
        runBlocking { store.search.search(query, candidates) }

    private fun ids(store: ConversationStore, query: String) =
        hits(store, query).hits.map { it.id }

    @Test fun titleMatchesNeedsEveryTermInTitle() {
        val store = ConversationStore(tmp.newFolder())
        val all = listOf(summary("a", "杭州旅行计划"), summary("b", "杭州"), summary("c", "旅行"))
        assertEquals(listOf("a"), store.search.titleMatches("杭州 计划", all).map { it.id })
        assertEquals(emptyList<String>(), store.search.titleMatches("杭州 会议", all).map { it.id })
    }

    @Test fun termsOfSplitsOnWhitespaceAndDropsBlanks() {
        assertEquals(listOf("杭州", "西湖"), ConversationSearch.termsOf("  杭州\t 西湖  "))
        assertEquals(emptyList<String>(), ConversationSearch.termsOf("   "))
        assertEquals(8, ConversationSearch.termsOf((1..12).joinToString(" ")).size)
        // 全角空格和 NBSP 也算分隔;重复的词只留一个
        assertEquals(listOf("杭州", "西湖"), ConversationSearch.termsOf("杭州\u3000西湖"))
        assertEquals(listOf("a", "b"), ConversationSearch.termsOf("a\u00A0b"))
        assertEquals(listOf("foo", "bar"), ConversationSearch.termsOf("foo foo bar"))
    }

    @Test fun fullWidthSpaceAndNbspSeparateTerms() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("杭州 西湖", "很好", "succeeded"))
        assertEquals(listOf("a"), ids(store, "杭州\u3000西湖"))
        assertEquals(listOf("a"), ids(store, "杭州\u00A0西湖"))
    }

    @Test fun findsChineseBodyTextAcrossTurns() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("帮我订票", "验证码一直过不去", "failed"))
        store.append("b", Turn("查天气", "明天晴", "succeeded"))

        val found = hits(store, "验证码")
        assertEquals(listOf("a"), found.hits.map { it.id })
        assertFalse(found.hits.single().inTitle)
        assertNotNull(found.hits.single().snippet)
    }

    @Test fun searchesSteersAndAssistantAnswers() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("买票", "已经买好", "succeeded", steers = listOf("改成靠窗")))
        assertEquals(listOf("a"), ids(store, "靠窗"))
        assertEquals(listOf("a"), ids(store, "买好"))
    }

    @Test fun everyTermMustAppearButTheyMayBeInDifferentPlaces() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查一下杭州", "西湖人很多", "succeeded"))
        store.append("b", Turn("查一下杭州", "天气不错", "succeeded"))
        assertEquals(listOf("a"), ids(store, "杭州 西湖"))
        assertEquals(emptyList<String>(), ids(store, "杭州 黄山"))
    }

    @Test fun ignoresCase() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("Book a FLIGHT", "Done", "succeeded"))
        assertEquals(listOf("a"), ids(store, "flight"))
        assertEquals(listOf("a"), ids(store, "FLIGHT"))
    }

    @Test fun ranksTitleHitsBeforeBodyHits() {
        val store = ConversationStore(tmp.newFolder())
        store.append("title", Turn("别的", "别的", "succeeded"))
        store.rename("title", "苹果")
        store.append("body", Turn("别的", "苹果", "succeeded"))
        store.rename("body", "香蕉")
        val all = store.list()
        val found = hits(store, "苹果", all)
        assertEquals("title", found.hits.first().id)
        assertTrue(found.hits.first().inTitle)
    }

    @Test fun newerWinsWhenScoresTie() {
        val store = ConversationStore(tmp.newFolder())
        store.append("old", Turn("问", "都提到葡萄", "succeeded"))
        store.append("new", Turn("问", "都提到葡萄", "succeeded"))
        val all = listOf(summary("old", "问", updatedAt = 1L), summary("new", "问", updatedAt = 2L))
        assertEquals(listOf("new", "old"), hits(store, "葡萄", all).hits.map { it.id })
    }

    @Test fun pinnedBreaksTies() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("问", "苹果", "succeeded"))
        store.append("b", Turn("问", "苹果", "succeeded"))
        val plain = summary("a", "问", updatedAt = 5L)
        val pinned = summary("b", "问", updatedAt = 5L, pinnedAt = 7L)
        assertEquals(listOf("b", "a"), hits(store, "苹果", listOf(plain, pinned)).hits.map { it.id })
    }

    @Test fun snippetKeepsContextAndMarksTruncation() {
        val store = ConversationStore(tmp.newFolder())
        val text = "开头" + "中".repeat(200) + "目标词" + "后".repeat(200)
        store.append("a", Turn("问", text, "succeeded"))
        val snippet = hits(store, "目标词").hits.single().snippet.orEmpty()
        assertTrue(snippet.contains("目标词"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test fun snippetDoesNotRepeatTheTitleWhenTitleIsTheFirstMessage() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("买牛奶", "好的", "succeeded"))
        store.rename("a", "买牛奶")
        assertNull(hits(store, "牛奶").hits.single().snippet)
    }

    @Test fun snippetDoesNotSplitEmoji() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("问", "😀".repeat(60) + "目标词", "succeeded"))
        val snippet = hits(store, "目标词").hits.single().snippet.orEmpty()
        assertFalse(hasLoneSurrogate(snippet))
    }

    @Test fun draftWithoutFileMatchesTitleOnly() {
        val store = ConversationStore(tmp.newFolder())
        val draft = summary("draft", "还没发出去的草稿", updatedAt = 0L)
        assertEquals(listOf("draft"), hits(store, "草稿", listOf(draft)).hits.map { it.id })
        assertEquals(emptyList<String>(), hits(store, "正文", listOf(draft)).hits.map { it.id })
    }

    @Test fun corruptFileDoesNotStopTitleSearch() {
        val dir = tmp.newFolder()
        File(dir, "a.json").writeText("{ not json")
        val store = ConversationStore(dir)
        val all = listOf(summary("a", "坏掉的对话", updatedAt = 1L))
        assertEquals(listOf("a"), hits(store, "坏掉", all).hits.map { it.id })
        assertEquals(emptyList<String>(), hits(store, "正文", all).hits.map { it.id })
    }

    @Test fun cacheReReadsOnlyWhenUpdatedAtChanges() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("旧问题", "旧回答", "succeeded"))
        val stale = summary("a", "旧问题", updatedAt = 1L)
        assertEquals(emptyList<String>(), hits(store, "新内容", listOf(stale)).hits.map { it.id })

        store.append("a", Turn("新内容", "新回答", "succeeded"))
        // 时间没变就还用缓存(缓存键是列表里的 updatedAt,不是文件本身)
        assertEquals(emptyList<String>(), hits(store, "新内容", listOf(stale)).hits.map { it.id })
        val fresh = summary("a", "旧问题", updatedAt = 2L)
        assertEquals(listOf("a"), hits(store, "新内容", listOf(fresh)).hits.map { it.id })
    }

    @Test fun clearDropsCachedBodies() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("旧问题", "旧回答", "succeeded"))
        val stale = summary("a", "旧问题", updatedAt = 1L)
        assertEquals(emptyList<String>(), hits(store, "新内容", listOf(stale)).hits.map { it.id })

        store.append("a", Turn("新内容", "新回答", "succeeded"))
        assertEquals(emptyList<String>(), hits(store, "新内容", listOf(stale)).hits.map { it.id })
        store.search.clear()
        assertEquals(listOf("a"), hits(store, "新内容", listOf(stale)).hits.map { it.id })
    }

    @Test fun emptyQueryMatchesNothing() {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("问", "答", "succeeded"))
        assertTrue(hits(store, "   ").hits.isEmpty())
        assertTrue(store.search.titleMatches("   ", store.list()).isEmpty())
    }

    private fun hasLoneSurrogate(s: String): Boolean = s.withIndex().any { (i, c) ->
        (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
    }
}
