package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeImageTest {
    @Test
    fun qrContainsDataAndQuietZone() {
        val matrix = encodeQr("https://example.invalid/bind?value=微信")
        assertTrue(matrix.size >= 29)
        assertEquals(matrix.size % 4, 1)
        for (i in 0 until matrix.size) {
            assertFalse(matrix[i, 0])
            assertFalse(matrix[i, 3])
            assertFalse(matrix[i, matrix.size - 1])
            assertFalse(matrix[0, i])
            assertFalse(matrix[3, i])
            assertFalse(matrix[matrix.size - 1, i])
        }
        assertTrue(matrix[4, 4])
        assertTrue(matrix[matrix.size - 5, 4])
        assertTrue(matrix[4, matrix.size - 5])
        assertTrue((0 until matrix.size).any { y -> (0 until matrix.size).any { x -> !matrix[x, y] } })
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankContentRejected() {
        encodeQr(" ")
    }
}
