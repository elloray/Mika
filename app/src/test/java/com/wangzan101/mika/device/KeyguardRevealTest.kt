package com.wangzan101.mika.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyguardRevealTest {
    private val window = KeyguardReveal.Bounds(0, 0, 1080, 2400)

    private fun b(left: Int, top: Int, right: Int, bottom: Int) = KeyguardReveal.Bounds(left, top, right, bottom)

    @Test fun noCandidatesOrAllTooSmallReturnNull() {
        assertNull(KeyguardReveal.fallbackTarget(emptyList(), window, 0.08f))
        // 小文字链接(取消/紧急呼叫)面积不到阈值,不算
        val small = listOf(
            KeyguardReveal.Candidate(b(0, 2200, 300, 2300), clickable = true, digit = false),
        )
        assertNull(KeyguardReveal.fallbackTarget(small, window, 0.08f))
    }

    @Test fun digitsAndNonClickableAreExcluded() {
        val candidates = listOf(
            KeyguardReveal.Candidate(b(200, 1600, 880, 2100), clickable = true, digit = true), // 数字键,排除
            KeyguardReveal.Candidate(b(200, 1600, 880, 2100), clickable = false, digit = false), // 不可点,排除
        )
        assertNull(KeyguardReveal.fallbackTarget(candidates, window, 0.08f))
    }

    @Test fun largestLargeClickableNonDigitWins() {
        val small = KeyguardReveal.Candidate(b(0, 2250, 260, 2350), clickable = true, digit = false)
        val fallback = KeyguardReveal.Candidate(b(200, 1600, 880, 2100), clickable = true, digit = false)
        val bigger = KeyguardReveal.Candidate(b(100, 1200, 980, 2000), clickable = true, digit = false)
        assertEquals(bigger.bounds, KeyguardReveal.fallbackTarget(listOf(small, fallback, bigger), window, 0.08f))
    }

    @Test fun candidatesOutsideTheWindowAreIgnored() {
        val outside = KeyguardReveal.Candidate(b(-500, 1600, 100, 2100), clickable = true, digit = false)
        assertNull(KeyguardReveal.fallbackTarget(listOf(outside), window, 0.08f))
    }

    @Test fun centerIsTheMidpoint() {
        assertEquals(540 to 2000, KeyguardReveal.center(b(200, 1800, 880, 2200)))
    }
}
