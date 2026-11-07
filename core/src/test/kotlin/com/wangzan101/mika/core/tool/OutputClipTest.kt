package com.wangzan101.mika.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputClipTest {
    @Test fun shortUntouched() {
        val s = "a".repeat(16000)
        assertEquals(s to false, OutputClip.clip(s))
        assertEquals("" to false, OutputClip.clip(""))
    }

    @Test fun longKeepsHeadAndTail() {
        val s = "H".repeat(4000) + "M".repeat(500) + "T".repeat(12000)
        val (out, clipped) = OutputClip.clip(s)
        assertTrue(clipped)
        assertTrue(out.startsWith("H".repeat(4000) + "\n…(500 characters omitted)…\n"))
        assertTrue(out.endsWith("T".repeat(12000)))
        assertFalse(out.contains("M"))
        val (small, c2) = OutputClip.clip("0123456789", head = 2, tail = 3)
        assertTrue(c2)
        assertEquals("01\n…(5 characters omitted)…\n789", small)
    }
}
