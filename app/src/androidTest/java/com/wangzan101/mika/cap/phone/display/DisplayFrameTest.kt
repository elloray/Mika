package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayFrameTest {
    @Test fun allFourRotationsRestorePixelsAndExcludeRowPadding() {
        // 物理内容 2×3,每行末尾另有一个填充像素。
        val source = Bitmap.createBitmap(intArrayOf(
            Color.RED, Color.GREEN, Color.MAGENTA,
            Color.BLUE, Color.YELLOW, Color.MAGENTA,
            Color.CYAN, Color.WHITE, Color.MAGENTA,
        ), 3, 3, Bitmap.Config.ARGB_8888)
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.WHITE),
            listOf(Color.GREEN, Color.YELLOW, Color.WHITE, Color.RED, Color.BLUE, Color.CYAN),
            listOf(Color.WHITE, Color.CYAN, Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.CYAN, Color.BLUE, Color.RED, Color.WHITE, Color.YELLOW, Color.GREEN),
        )
        try {
            for (rotation in 0..3) {
                val geometry = DisplayFrameGeometry(2, 3, rotation)
                val result = uprightDisplayFrame(source, geometry)
                try {
                    assertEquals(geometry.width, result.width)
                    assertEquals(geometry.height, result.height)
                    val pixels = IntArray(result.width * result.height)
                    result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
                    assertEquals("rotation $rotation", expected[rotation], pixels.toList())
                } finally { result.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun previewScalesAfterRotationAndKeepsEveryCorner() {
        val source = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 20) {
            source.setPixel(x, y, when {
                x < 10 && y < 20 -> Color.RED
                y < 20 -> Color.GREEN
                x < 10 -> Color.BLUE
                else -> Color.YELLOW
            })
        }
        val preview = uprightDisplayFrame(source, DisplayFrameGeometry(20, 40, 1), maxWidth = 20)
        try {
            assertEquals(20, preview.width)
            assertEquals(10, preview.height)
            assertEquals(Color.GREEN, preview.getPixel(2, 2))
            assertEquals(Color.YELLOW, preview.getPixel(17, 2))
            assertEquals(Color.RED, preview.getPixel(2, 7))
            assertEquals(Color.BLUE, preview.getPixel(17, 7))
        } finally { preview.recycle(); source.recycle() }
    }
}
