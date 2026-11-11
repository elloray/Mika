package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PageBookmarksTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun store(limit: Int = PageBookmarks.LIMIT) = PageBookmarks(tmp.root, limit, clock = { now })

    @Test
    fun newestFirstAndPersisted() {
        val b = store()
        assertTrue(b.add("https://a.example.com/", "A")); now += 10
        assertTrue(b.add("https://b.example.com/", null)); now += 10
        assertTrue(b.add("https://c.example.com/", "C"))
        assertEquals(listOf("https://c.example.com/", "https://b.example.com/", "https://a.example.com/"), b.entries.value.map { it.url })
        assertEquals("", b.entries.value[1].title)
        assertEquals(b.entries.value, store().entries.value)
    }

    @Test
    fun sameAddressMovesToTopOnce() {
        val b = store()
        b.add("https://a.example.com/x", "旧"); now += 10
        b.add("https://b.example.com/", "B"); now += 10
        b.add("https://a.example.com/x#section", "新")
        val list = b.entries.value
        assertEquals(2, list.size)
        assertEquals("https://a.example.com/x#section", list[0].url)
        assertEquals("新", list[0].title)
    }

    @Test
    fun keepsOnlyTheLimit() {
        val b = store(limit = 3)
        (1..5).forEach { b.add("https://s$it.example.com/", "S$it"); now += 1 }
        assertEquals(listOf("S5", "S4", "S3"), b.entries.value.map { it.title })
    }

    @Test
    fun removeAndContains() {
        val b = store()
        b.add("https://a.example.com/x", "A")
        assertTrue(b.contains("https://a.example.com/x#top"))
        assertTrue(b.remove("https://a.example.com/x"))
        assertFalse(b.contains("https://a.example.com/x"))
        assertFalse(b.remove("https://a.example.com/x"))
        assertEquals(emptyList<PageBookmark>(), b.entries.value)
    }

    @Test
    fun retitleOnlyChangesExistingAndKeepsOrder() {
        val b = store()
        b.add("https://a.example.com/", "A"); now += 10
        b.add("https://b.example.com/", "B")
        b.retitle("https://a.example.com/", "A2")
        b.retitle("https://c.example.com/", "C")
        assertEquals(listOf("B", "A2"), b.entries.value.map { it.title })
        b.retitle("https://b.example.com/", "  ")
        assertEquals(listOf("B", "A2"), b.entries.value.map { it.title })
    }

    @Test
    fun renameChangesTitle() {
        val b = store()
        b.add("https://a.example.com/", "A")
        b.rename("https://a.example.com/", "  新标题 ")
        assertEquals("新标题", b.entries.value.single().title)
        b.rename("https://a.example.com/", "   ")
        assertEquals("新标题", b.entries.value.single().title)
    }

    @Test
    fun userRenamedTitlesSurvivePageRetitleAndRestart() {
        val b = store()
        b.add("https://a.example.com/", "A")
        b.rename("https://a.example.com/", "买鞋")
        b.retitle("https://a.example.com/", "商品详情")
        assertEquals("买鞋", b.entries.value.single().title)
        val reloaded = store()
        assertEquals("买鞋", reloaded.entries.value.single().title)
        assertTrue(reloaded.entries.value.single().edited)
        reloaded.retitle("https://a.example.com/", "又变了")
        assertEquals("买鞋", reloaded.entries.value.single().title)
    }

    @Test
    fun onlyHttpPagesAreStored() {
        val b = store()
        assertFalse(b.add("ftp://example.com/x", "FTP"))
        assertFalse(b.add("javascript:alert(1)", "JS"))
        assertFalse(b.add("about:blank", "Blank"))
        assertFalse(b.add("https://", "Empty"))
        assertFalse(b.add("https:///path", "No host"))
        assertTrue(b.add("http://localhost:8080/x", "Local"))
        assertEquals(listOf("http://localhost:8080/x"), b.entries.value.map { it.url })
    }

    @Test
    fun brokenFileStartsEmpty() {
        File(tmp.root, "browser_bookmarks.json").writeText("nope")
        assertEquals(emptyList<PageBookmark>(), PageBookmarks(tmp.root).entries.value)
    }
}
