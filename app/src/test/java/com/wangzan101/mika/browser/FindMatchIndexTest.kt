package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class FindMatchIndexTest {
    @Test
    fun webViewOrdinalIsZeroBased() {
        assertEquals(1, findMatchIndex(active = 0, matches = 3))
        assertEquals(2, findMatchIndex(active = 1, matches = 3))
        assertEquals(3, findMatchIndex(active = 2, matches = 3))
    }

    @Test
    fun noMatchesShowsZero() {
        assertEquals(0, findMatchIndex(active = 0, matches = 0))
        assertEquals(0, findMatchIndex(active = -1, matches = 0))
    }

    @Test
    fun outOfRangeOrdinalIsClamped() {
        assertEquals(1, findMatchIndex(active = -1, matches = 3))
        assertEquals(3, findMatchIndex(active = 9, matches = 3))
    }
}
