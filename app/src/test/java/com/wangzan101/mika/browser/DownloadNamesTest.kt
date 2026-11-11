package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamesTest {
    @Test
    fun sanitizeReplacesPathAndReservedCharacters() {
        assertEquals("a_b_c.txt", DownloadNames.sanitize("a/b\\c.txt"))
        assertEquals("a_b_c_d_e.txt", DownloadNames.sanitize("a:b*c?d\"e.txt"))
        assertEquals("hello.txt", DownloadNames.sanitize("  hello.txt.  "))
        assertEquals("download", DownloadNames.sanitize("   "))
        assertEquals("download", DownloadNames.sanitize("..."))
    }

    @Test
    fun uniqueAddsACounterBeforeTheExtension() {
        assertEquals("a.txt", DownloadNames.unique("a.txt", emptySet()))
        assertEquals("a (2).txt", DownloadNames.unique("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", DownloadNames.unique("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("README (2)", DownloadNames.unique("README", setOf("README")))
    }

    @Test
    fun longNamesKeepTheExtension() {
        val name = DownloadNames.sanitize("a".repeat(200) + ".pdf")
        assertTrue(name.length <= 100)
        assertTrue(name.endsWith(".pdf"))
    }
}
