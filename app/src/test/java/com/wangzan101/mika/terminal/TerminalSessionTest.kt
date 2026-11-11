package com.wangzan101.mika.terminal

import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TerminalSessionTest {
    private class FakeShell : SandboxShell {
        private val toApp = PipedInputStream(64 * 1024)
        private val fromApp = PipedInputStream(64 * 1024)
        val writer = PipedOutputStream(toApp)
        private val appWriter = PipedOutputStream(fromApp)
        val appWritten: InputStream = fromApp
        override val input: OutputStream = appWriter
        override val output: InputStream = toApp
        override val exited = CompletableDeferred<Int>()
        override val revoked = MutableStateFlow(false)
        @Volatile var closed = false
        override fun close() {
            closed = true
            runCatching { writer.close() }
            runCatching { appWriter.close() }
            runCatching { toApp.close() }
            runCatching { fromApp.close() }
        }
    }

    @Test fun outputGoesToRingBufferAndStateTracksExit() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c1", "one", network = false, shell = shell, scope = scope)
        try {
            shell.writer.write("hello\n".toByteArray()); shell.writer.flush()
            withTimeout(2_000) {
                while (!String(session.snapshot()).contains("hello")) Thread.sleep(10)
            }
            assertEquals("hello\n", String(session.snapshot()))

            val input = shell.appWritten
            session.write("ls -la")
            val got = ByteArray(16)
            val n = withTimeout(2_000) { input.read(got) }
            assertEquals("ls -la\n", String(got, 0, n))
            assertEquals("ls -la", session.history.last())

            shell.writer.close()
            shell.exited.complete(7)
            withTimeout(2_000) {
                while (session.phase.value != TerminalSession.Phase.Exited) Thread.sleep(10)
            }
            assertEquals(7, session.exitCode.value)
            withTimeout(2_000) {
                while (!shell.closed || shell.revoked.subscriptionCount.value != 0) delay(10)
            }
        } finally {
            session.close()
            scope.cancel()
        }
        assertTrue(shell.closed)
    }

    /** 界面一时跟不上(主线程卡一下)时不丢块:丢掉的块会让屏上缺一段,还可能切断转义序列或半个字。 */
    @Test fun aViewerThatFallsBehindStillGetsEveryChunk() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c3", null, network = false, shell = shell, scope = scope)
        val seen = ByteArrayOutputStream()
        val subscribed = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val viewer = scope.launch {
                session.output.onSubscription { subscribed.complete(Unit) }.collect { chunk ->
                    holding.complete(Unit)
                    resume.await()
                    synchronized(seen) { seen.write(chunk) }
                }
            }
            withTimeout(2_000) { subscribed.await() }
            // 前 66 行一行一块地写:界面手里 1 块、缓冲 64 块,第 66 块要么挤掉最旧的,要么让读输出的一侧等着。
            // 每块都是先进环形缓冲再交给界面,所以两种写法下这 66 行都等得到;后面的一次写完
            for (i in 1..66) {
                val line = "line-$i\n"
                shell.writer.write(line.toByteArray()); shell.writer.flush()
                withTimeout(2_000) { while (!String(session.snapshot()).endsWith(line)) delay(2) }
                // 界面先拿到第 1 块再写后面的,缓冲才是从第 2 块开始装
                if (i == 1) withTimeout(2_000) { holding.await() }
            }
            shell.writer.write((67..100).joinToString("") { "line-$it\n" }.toByteArray()); shell.writer.flush()
            resume.complete(Unit)
            val all = (1..100).joinToString("") { "line-$it\n" }
            withTimeout(5_000) { while (String(session.snapshot()) != all) delay(10) }
            withTimeoutOrNull(2_000) { while (synchronized(seen) { seen.size() } < all.length) delay(10) }
            assertEquals(all, synchronized(seen) { seen.toString() })
            viewer.cancel()
        } finally {
            session.close()
            scope.cancel()
        }
    }

    /** 输入没人读(沙箱那头在等界面)时,写输入也不能卡住调用方:按键行是在主线程上写的。 */
    @Test fun writingDoesNotBlockTheCallerWhenInputIsNotRead() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c4", null, network = false, shell = shell, scope = scope)
        try {
            val caller = Thread { session.writeBytes(ByteArray(200_000)); session.writeBytes("\u001b[A".toByteArray()) }
            caller.start()
            caller.join(2_000)
            assertFalse("writeBytes waited for the shell to read its input", caller.isAlive)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test fun revocationWinsOverExit() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c2", null, network = false, shell = shell, scope = scope)
        try {
            shell.revoked.value = true
            withTimeout(2_000) {
                while (session.phase.value != TerminalSession.Phase.Revoked) Thread.sleep(10)
            }
            assertEquals(TerminalSession.Phase.Revoked, session.phase.value)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test fun ringBufferKeepsTheNewestBytes() {
        val ring = TerminalRingBuffer(8)
        ring.append("abc".toByteArray())
        ring.append("defgh".toByteArray())
        assertEquals("abcdefgh", String(ring.snapshot()))
        assertEquals(0, ring.droppedBytes())
        ring.append("ijkl".toByteArray())
        assertEquals("efghijkl", String(ring.snapshot()))
        assertEquals(4, ring.droppedBytes())
        ring.append("0123456789".toByteArray())
        assertEquals("23456789", String(ring.snapshot()))
        assertEquals(6, ring.droppedBytes())
    }
}
