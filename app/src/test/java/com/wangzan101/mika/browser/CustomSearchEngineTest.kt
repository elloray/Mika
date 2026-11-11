package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSearchEngineTest {
    @Test
    fun createValidatesNameAndTemplate() {
        assertNull(CustomSearchEngine.create("", "https://example.com/?q=%s"))
        assertNull(CustomSearchEngine.create("  ", "https://example.com/?q=%s"))
        assertNull(CustomSearchEngine.create("Example", "https://example.com/?q="))
        assertNull(CustomSearchEngine.create("Example", "https://example.com/?q=%s&r=%s"))
        assertNull(CustomSearchEngine.create("Example", "ftp://example.com/?q=%s"))
        assertNull(CustomSearchEngine.create("Example", "not a url %s"))
        assertNull(CustomSearchEngine.create("Example", "https://example.com/?q=%s extra"))
        // 占位符要在主机名后面
        assertNull(CustomSearchEngine.create("Example", "https://%s.example.com/?q=x"))
    }

    @Test
    fun createTrimsAndBuildsASearchUrl() {
        val e = CustomSearchEngine.create("  My search  ", "  https://example.com/search?q=%s  ")
        assertNotNull(e)
        assertEquals("My search", e!!.displayName)
        assertTrue(e.id.startsWith("custom:"))
        assertEquals("https://example.com/search?q=hello+world", e.searchUrl(" hello world "))
        assertEquals("hello world", e.queryOf(e.searchUrl("hello world")))
    }
}
