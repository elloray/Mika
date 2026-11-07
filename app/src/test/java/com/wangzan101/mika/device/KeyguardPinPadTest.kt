package com.wangzan101.mika.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardPinPadTest {
    private val window = KeyguardPinPad.Bounds(0, 0, 1080, 2400)

    private fun key(digit: Char, x: Int, y: Int, width: Int = 180, height: Int = 140) =
        KeyguardPinPad.DigitKey(digit, KeyguardPinPad.Bounds(x - width / 2, y - height / 2, x + width / 2, y + height / 2))

    private fun pad(): MutableList<KeyguardPinPad.DigitKey> {
        val keys = mutableListOf<KeyguardPinPad.DigitKey>()
        val rows = listOf("123", "456", "789")
        for ((row, digits) in rows.withIndex()) {
            for ((column, digit) in digits.withIndex()) keys += key(digit, 200 + column * 340, 1000 + row * 300)
        }
        keys += key('0', 540, 1900)
        return keys
    }

    @Test fun completeSpatiallyCoherentPadIsAPinTarget() {
        assertTrue(KeyguardPinPad.matches(pad(), window))
    }

    @Test fun parentAndChildNodesForTheSameKeyAreDeduplicated() {
        val keys = pad()
        keys += keys[4].copy()
        keys += key('5', 540, 1300, width = 240, height = 200)
        assertTrue(KeyguardPinPad.matches(keys, window))
    }

    @Test fun missingOrPositionallyAmbiguousDigitsAreRejected() {
        assertFalse(KeyguardPinPad.matches(pad().drop(1), window))
        val duplicate = pad().also { it += key('5', 700, 1300) }
        assertFalse(KeyguardPinPad.matches(duplicate, window))
        val unknown = pad().also { it += key('x', 540, 2200) }
        assertFalse(KeyguardPinPad.matches(unknown, window))
    }

    @Test fun incompleteOrUnrelatedNumericPagesDoNotBecomePinPads() {
        assertFalse(KeyguardPinPad.matches(emptyList(), window))
        assertFalse(KeyguardPinPad.matches(pad().take(9).filterNot { it.digit == '7' }, window))
        val scattered = pad().mapIndexed { index, key ->
            if (index == 5) key.copy(bounds = key.bounds.copy(left = key.bounds.left + 120, right = key.bounds.right + 120)) else key
        }
        assertFalse(KeyguardPinPad.matches(scattered, window))
        val misplacedZero = pad().map { if (it.digit == '0') key('0', 200, 1900) else it }
        assertFalse(KeyguardPinPad.matches(misplacedZero, window))
    }

    @Test fun unevenRowsAndColumnsAreNotCoherent() {
        val compressedTop = pad().map {
            if (it.digit in "123") key(it.digit, 200 + (it.digit - '1') * 100, 1000) else it
        }
        assertFalse(KeyguardPinPad.matches(compressedTop, window))
        val distantZero = pad().map { if (it.digit == '0') key('0', 540, 2250) else it }
        assertFalse(KeyguardPinPad.matches(distantZero, window))
    }

    @Test fun invalidOrOutOfWindowCoordinatesAreRejected() {
        val inverted = pad().map {
            if (it.digit == '1') it.copy(bounds = it.bounds.copy(left = it.bounds.right)) else it
        }
        assertFalse(KeyguardPinPad.matches(inverted, window))
        val negative = pad().map {
            if (it.digit == '2') it.copy(bounds = it.bounds.copy(left = -1)) else it
        }
        assertFalse(KeyguardPinPad.matches(negative, window))
        val beyondBottom = pad().map { if (it.digit == '0') key('0', 540, 2450) else it }
        assertFalse(KeyguardPinPad.matches(beyondBottom, window))
        assertFalse(KeyguardPinPad.matches(pad(), window.copy(right = window.left)))
        val extreme = pad().map {
            if (it.digit == '3') it.copy(bounds = KeyguardPinPad.Bounds(Int.MIN_VALUE, it.bounds.top, Int.MAX_VALUE, it.bounds.bottom)) else it
        }
        assertFalse(KeyguardPinPad.matches(extreme, window))
    }

    @Test fun recognitionDoesNotDependOnFixedScreenCoordinates() {
        val shifted = pad().map { key ->
            key.copy(bounds = key.bounds.copy(left = key.bounds.left + 40, top = key.bounds.top - 80,
                right = key.bounds.right + 40, bottom = key.bounds.bottom - 80))
        }
        assertTrue(KeyguardPinPad.matches(shifted, window))
    }
}
