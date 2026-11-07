package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayOrientationTest {
    @Test fun landscapeLetterboxTurnsPortraitDisplay() {
        assertEquals(1, DisplayOrientation.rotation(720, 1600, 0, 720, 324))
    }
    @Test fun portraitLetterboxTurnsItBack() {
        assertEquals(0, DisplayOrientation.rotation(720, 1600, 1, 324, 720))
    }
    @Test fun alreadyMatchingAxesLeaveSystemRotationAlone() {
        for (rotation in listOf(1, 3)) assertNull(DisplayOrientation.rotation(720, 1600, rotation, 1600, 720))
        for (rotation in listOf(0, 2)) assertNull(DisplayOrientation.rotation(720, 1600, rotation, 720, 1600))
    }
    @Test fun naturalLandscapeAndInvalidWindowsAreHandled() {
        assertEquals(1, DisplayOrientation.rotation(1600, 720, 0, 324, 720))
        assertNull(DisplayOrientation.rotation(720, 1600, 0, -1, -1))
        assertNull(DisplayOrientation.rotation(720, 1600, 0, 500, 500))
    }
    @Test fun fixedActivityOrientationFillsMissingWindowGeometry() {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)) {
            assertEquals(1, DisplayOrientation.rotation(720, 1600, 0, -1, -1, orientation))
            assertNull(DisplayOrientation.rotation(720, 1600, 1, -1, -1, orientation))
        }
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)) {
            assertEquals(0, DisplayOrientation.rotation(720, 1600, 1, -1, -1, orientation))
            assertNull(DisplayOrientation.rotation(720, 1600, 0, -1, -1, orientation))
        }
    }
    @Test fun unconstrainedActivityOrientationDoesNotInventRotation() {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, ActivityInfo.SCREEN_ORIENTATION_LOCKED)) {
            assertNull(DisplayOrientation.rotation(720, 1600, 0, -1, -1, orientation))
        }
    }
    @Test fun realWindowGeometryTakesPrecedenceOverUnconstrainedRequest() {
        assertEquals(1, DisplayOrientation.rotation(720, 1600, 0, 720, 324,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }
}
