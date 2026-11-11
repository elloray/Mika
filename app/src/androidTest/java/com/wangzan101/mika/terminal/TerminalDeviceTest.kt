package com.wangzan101.mika.terminal

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 spike:确认 PRoot 里的 pty 能用(用户终端的 P0 门槛)。
 * 需要 arm64 设备、已装好运行环境;其他设备上自动跳过。
 */
@RunWith(AndroidJUnit4::class)
class TerminalDeviceTest {
    private val app: MikaApp get() = ApplicationProvider.getApplicationContext()
    private val output = StringBuilder()
    private var shell: com.wangzan101.mika.core.cap.sandbox.SandboxShell? = null

    @Before
    fun setUp() {
        val ready = runBlocking {
            val sandbox = AndroidSandbox(app, SandboxState.get(app))
            sandbox.awaitReady(120_000)
        }
        assumeTrue("sandbox must be ready", ready is SandboxStatus.Ready)
    }

    @After
    fun tearDown() {
        runCatching { shell?.close() }
    }

    @Test
    fun ptyShellBasicsAndCtrlC() = runBlocking {
        val sandbox = AndroidSandbox(app, SandboxState.get(app))
        val s = sandbox.openShell("terminal-device-test-${System.currentTimeMillis()}", 80, 24)
        shell = s
        val reader = launch(Dispatchers.IO) {
            runCatching {
                s.output.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { output.append(line).append('\n') } }
                }
            }
        }
        try {
            send(s, "tty\n")
            await("/dev/pts/")
            send(s, "stty size\n")
            await("24 80")
            send(s, "echo MARK_$((1+1))\n")
            await("MARK_2")

            s.resize(100, 40)
            delay(500)
            send(s, "stty size\n")
            await("40 100")

            send(s, "sleep 30\n")
            delay(1_000)
            s.input.write(byteArrayOf(3)) // Ctrl+C
            s.input.flush()
            delay(500)
            send(s, "echo AFTER_$((2+2))\n")
            await("AFTER_4", 8_000)
            Log.i("TerminalDeviceTest", output.toString())
        } finally {
            reader.cancel()
        }
    }

    private fun send(s: com.wangzan101.mika.core.cap.sandbox.SandboxShell, text: String) {
        s.input.write(text.toByteArray())
        s.input.flush()
    }

    private suspend fun await(needle: String, ms: Long = 10_000) {
        withTimeout(ms) {
            while (true) {
                if (synchronized(output) { output.contains(needle) }) return@withTimeout
                delay(50)
            }
        }
        assertTrue("missing $needle in:\n$output", synchronized(output) { output.contains(needle) })
    }
}
