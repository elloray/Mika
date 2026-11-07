package com.wangzan101.mika.capsule

import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMotionTest {
    @Test fun reducedMotionSkipsTheAnimation() = runTest {
        var animated = false
        runOverlayClose(reduceMotion = true) { animated = true }
        assertFalse(animated)
    }

    @Test fun normalAnimationFinishesBeforeClose() = runTest {
        val events = mutableListOf<String>()
        runOverlayClose(reduceMotion = false) {
            events += "start"
            delay(100)
            events += "end"
        }
        events += "closed"
        assertEquals(listOf("start", "end", "closed"), events)
    }

    @Test fun missingFramesCannotKeepTheOverlayClosingForever() = runTest {
        var returned = false
        runOverlayClose(reduceMotion = false) { awaitCancellation() }
        returned = true
        assertTrue("动画永远不来下一帧时也要回到调用方收尾", returned)
    }

    @Test fun systemSlowMotionGetsEnoughTimeToFinish() = runTest {
        var finished = false
        val slow = object : MotionDurationScale {
            override val scaleFactor = 10f
        }
        withContext(slow) {
            runOverlayClose(reduceMotion = false) {
                delay(1_000)
                finished = true
            }
        }
        assertTrue(finished)
    }
}
