package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import android.media.ImageWriter
import android.view.Display
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class AgentDisplayTimestampTest {
    @Test fun aLateReadKeepsTheProducersFrameTimestamp() = runBlocking {
        lateinit var output: Surface
        val shell = Proxy.newProxyInstance(IMikaShell::class.java.classLoader, arrayOf(IMikaShell::class.java)) { _, method, args ->
            when (method.name) {
                "createDisplay" -> {
                    output = args!![0] as Surface
                    Display.DEFAULT_DISPLAY
                }
                "releaseDisplay" -> null
                else -> error("Unexpected shell call: ${method.name}")
            }
        } as IMikaShell
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = AgentDisplay(shell, 64, 96, 160, context.getSystemService(DisplayManager::class.java))
        try {
            ImageWriter.newInstance(output, 1).use { writer ->
                // The buffer represents an older frame even though it is only consumed now.
                val oldTimestamp = System.nanoTime() - 1_000_000_000L
                writer.queueInputImage(writer.dequeueInputImage().apply { timestamp = oldTimestamp })
                withTimeout(5_000) { display.frames.filterNotNull().first() }
                assertEquals(oldTimestamp, display.lastFrameAt())
            }
        } finally {
            display.release()
        }
    }
}
