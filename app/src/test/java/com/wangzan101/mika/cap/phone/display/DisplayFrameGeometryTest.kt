package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFrameGeometryTest {
    @Test fun portraitAndUpsideDownKeepLogicalDimensions() {
        for (rotation in listOf(0, 2)) {
            val geometry = DisplayFrameGeometry(720, 1600, rotation)
            assertEquals(720, geometry.width)
            assertEquals(1600, geometry.height)
            assertEquals(360 to 800, geometry.previewSize(geometry.previewWidth(360)))
        }
    }

    @Test fun bothLandscapeDirectionsSwapDimensionsAndPreviewAspect() {
        for (rotation in listOf(1, 3)) {
            val geometry = DisplayFrameGeometry(720, 1600, rotation)
            assertEquals(1600, geometry.width)
            assertEquals(720, geometry.height)
            assertEquals(800 to 360, geometry.previewSize(geometry.previewWidth(360)))
        }
    }

    @Test fun naturallyLandscapeDisplayCanRotateToPortrait() {
        for ((rotation, expected) in listOf(1 to (360 to 800), 0 to (800 to 360))) {
            val geometry = DisplayFrameGeometry(1600, 720, rotation)
            assertEquals(expected, geometry.previewSize(geometry.previewWidth(360)))
        }
    }

    @Test fun previewDoesNotUpscaleSmallFrames() {
        val geometry = DisplayFrameGeometry(36, 80, 3)
        assertEquals(80 to 36, geometry.previewSize(geometry.previewWidth(360)))
    }
}
