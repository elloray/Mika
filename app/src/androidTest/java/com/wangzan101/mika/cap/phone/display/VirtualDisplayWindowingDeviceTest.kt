package com.wangzan101.mika.cap.phone.display

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.selfcheck.SelfCheckTargetActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 有的系统默认把虚拟屏上的 App 开成小窗:Mika 的屏要是全屏模式;同时检查无障碍读到的实际窗口范围,不只检查屏的分辨率。 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayWindowingDeviceTest {
    @Test
    fun testPageFillsVirtualDisplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shell = DeviceSupport.shell()
        val a11y = DeviceSupport.service()
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, context.getSystemService(DisplayManager::class.java))
        val mainTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        try {
            assertEquals(0, shell.startSystemAction(display.displayId, SelfCheckTargetActivity.intent(context), null))
            var lastBounds: Rect? = null
            // 等启动动画结束,不能把从小窗展开过程中的第一帧当成最终窗口大小。
            val bounds = DeviceSupport.pollUntil(5_000) {
                a11y.windowsOn(display.displayId).firstOrNull { it.root?.packageName == context.packageName }
                    ?.let { Rect().apply { it.getBoundsInScreen(this) } }
                    ?.also { lastBounds = it }
                    ?.takeIf {
                        it.left in 0..(display.width / 40) && it.right in (display.width * 39 / 40)..display.width &&
                            it.height() >= display.height * 0.9
                    }
            }
            assertNotNull("window $lastBounds does not fill ${display.width}x${display.height}", bounds)
            assertEquals("main screen task changed", mainTask, shell.topTaskId(Display.DEFAULT_DISPLAY))
        } finally {
            display.release()
        }
    }
}
