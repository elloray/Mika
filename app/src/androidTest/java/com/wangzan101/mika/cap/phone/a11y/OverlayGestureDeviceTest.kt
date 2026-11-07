package com.wangzan101.mika.cap.phone.a11y

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 分别验证正常与取消的注入不会误点浮层,结束后普通点按恢复。
 * 窗口参数传到输入系统是异步的;外层隔离先等系统稳定,嵌套手势不再改参数,
 * 因而这里验证的是隔离与恢复的状态机,不把固定帧数当成生产代码的同步机制。
 */
@RunWith(AndroidJUnit4::class)
class OverlayGestureDeviceTest {
    @Test fun normalAndCancelledInjectionPassThroughThenRestoreTouch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = DeviceSupport.service()
        val testPackage = instrumentation.context.packageName
        DeviceSupport.sh("am start --display ${Display.DEFAULT_DISPLAY} -n $testPackage/${GestureTapTestActivity::class.java.name}")
        assertEquals(0, DeviceSupport.pollUntil(5_000) { pageTaps(service, testPackage).takeIf { it == 0 } })

        val overlayTaps = AtomicInteger()
        var overlay: OverlayWindow? = null
        try {
            instrumentation.runOnMainSync {
                overlay = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT, 80, 600)) {
                    Box(Modifier.size(96.dp).background(Color(0xFF356859)).clickable {
                        overlayTaps.incrementAndGet()
                    })
                }
            }
            val window = overlay
            assertNotNull("test overlay could not be opened", window)
            assertNotNull("overlay was not laid out", DeviceSupport.pollUntil(5_000) { true.takeIf { window!!.width > 0 && window.height > 0 } })
            instrumentation.waitForIdleSync()
            val x = 80 + window!!.width / 2
            val y = 600 + window.height / 2

            suspend fun inject(cancel: Boolean) {
                service.overlays().passInjectedGesturesThrough {
                    delay(INPUT_SETTLE_MS)
                    val injected = async(start = CoroutineStart.UNDISPATCHED) {
                        service.gesture(
                            Display.DEFAULT_DISPLAY,
                            Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                            2_000,
                        )
                    }
                    if (cancel) {
                        delay(80)
                        injected.cancel()
                        withTimeout(5_000) { injected.join() }
                    } else {
                        assertTrue("system gesture was not dispatched", withTimeout(5_000) { injected.await() })
                    }
                }
            }

            inject(cancel = false)
            assertEquals("normal injection must not tap the overlay", 0, overlayTaps.get())
            assertNotNull("normal injection did not reach the page", DeviceSupport.pollUntil(3_000) { pageTaps(service, testPackage).takeIf { it == 1 } })

            delay(INPUT_SETTLE_MS)
            userTap(instrumentation, x, y)
            assertNotNull("overlay did not restore after normal injection", DeviceSupport.pollUntil(3_000) { true.takeIf { overlayTaps.get() == 1 } })
            assertEquals(1, pageTaps(service, testPackage))
            overlayTaps.set(0)

            inject(cancel = true)
            assertEquals("cancelled injection must not tap the overlay", 0, overlayTaps.get())
            assertNotNull("cancelled injection did not reach the page", DeviceSupport.pollUntil(3_000) { pageTaps(service, testPackage).takeIf { it == 2 } })

            delay(INPUT_SETTLE_MS)
            userTap(instrumentation, x, y)
            assertNotNull("overlay stayed untouchable after cancellation", DeviceSupport.pollUntil(3_000) { true.takeIf { overlayTaps.get() == 1 } })
            assertEquals("restored user touch leaked to the page", 2, pageTaps(service, testPackage))
        } finally {
            instrumentation.runOnMainSync { overlay?.close() }
            assertTrue("test page did not close", instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        }
    }

    private fun userTap(instrumentation: Instrumentation, x: Int, y: Int) {
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun pageTaps(service: ScreenAccessibility, testPackage: String): Int {
        val found = mutableListOf<Int>()
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != testPackage) continue
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val text = node.text?.toString().orEmpty()
                if (text.startsWith("taps:")) found += text.removePrefix("taps:").toIntOrNull() ?: return -1
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return found.singleOrNull() ?: -1
    }

    private companion object { const val INPUT_SETTLE_MS = 500L }
}
