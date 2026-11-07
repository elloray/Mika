package com.wangzan101.mika.capsule

import android.app.UiAutomation
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.GestureTapTestActivity
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.settings.FeatureFlags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 真实的两个浮层窗口:收起后无障碍节点消失还不够,系统画布上也不能留下半张卡片。 */
@RunWith(AndroidJUnit4::class)
class CapsuleWindowDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MikaApp
    private val flags = FeatureFlags.get(app)

    @Test fun closedCardLeavesNoPixelsBehind() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        try {
            flags.setQuitRequested(false)
            flags.setCapsuleMode(CapsuleMode.Always)
            app.capsule.resume()
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            Thread.sleep(300) // 等页面进入动画结束,再记录用于判断残帧的静态基准
            assertNotNull("idle capsule did not appear", pollUntil(15_000) { find(service, CapsuleCopy.IDLE) })
            val baseline = screenshot()

            assertTrue("idle capsule was not clickable", click(service, CapsuleCopy.IDLE))
            assertNotNull("card did not open", pollUntil(5_000) { find(service, CardCopy.COLLAPSE) })
            val opened = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it > 0.002f } }
            assertNotNull("card did not visibly change the page", opened)

            assertTrue("card close button was not clickable", click(service, CardCopy.COLLAPSE))
            assertNotNull("card accessibility nodes did not close", pollUntil(5_000) {
                true.takeIf { find(service, CardCopy.COLLAPSE) == null }
            })
            val remaining = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it <= 0.002f } }
            assertNotNull("card pixels remained after closing", remaining)
            Thread.sleep(300)
            assertTrue(
                "card pixels returned after closing",
                changedFraction(baseline, screenshot()) <= 0.002f,
            )
        } finally {
            find(service, CardCopy.COLLAPSE)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            app.capsule.resume()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** 自己服务的浮层也在窗口列表里;只找用户看得见的节点,不可见窗口里的旧 Compose 树不算。 */
    private fun find(service: ScreenAccessibility, description: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.contentDescription?.toString() == description) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun findText(service: ScreenAccessibility, text: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.text?.toString() == text) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun click(service: ScreenAccessibility, description: String): Boolean =
        find(service, description)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** 页面中段的像素;避开状态栏、顶部胶囊和底部导航条。 */
    private fun screenshot(): IntArray {
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        try {
            val x = bitmap.width / 10
            val y = bitmap.height / 3
            val width = bitmap.width * 8 / 10
            val height = bitmap.height / 3
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, x, y, width, height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun changedFraction(before: IntArray, after: IntArray): Float {
        if (before.size != after.size) return 1f
        var changed = 0
        for (i in before.indices) {
            val a = before[i]
            val b = after[i]
            val difference = maxOf(
                abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                abs((a and 0xff) - (b and 0xff)),
            )
            if (difference > 32) changed++
        }
        return changed.toFloat() / before.size
    }
}
