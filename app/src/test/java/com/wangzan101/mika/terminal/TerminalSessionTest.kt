package com.wangzan101.mika.terminal

import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
