package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 边说边转的会话:按住时上传、收结果,松开后收尾,只在服务端说结束时给 Final。用自建 Fun-ASR 的协议跑。 */
class LiveSpeechTest {
    private class Capture(private val starts: Boolean = true) : StreamingRecorder {
        override val level = MutableStateFlow(0f)
        override val chunks = Channel<ShortArray>(100)
        var cancelled = false
        override fun start() = starts
        override fun stop(): ShortArray { chunks.close(); return shortArrayOf() }
        override fun cancel() { cancelled = true; chunks.cancel() }
    }

    private sealed interface Out {
        data class Text(val text: String) : Out
        class Audio(val pcm: ByteArray) : Out
    }

    private class Socket : LiveAsrSocket {
        override val incoming = Channel<String>(32)
        val sent = Channel<Out>(64)
        var closed = false
        override fun sendText(text: String) { check(sent.trySend(Out.Text(text)).isSuccess) }
        override fun sendAudio(pcm: ByteArray) { check(sent.trySend(Out.Audio(pcm)).isSuccess) }
        override fun close() { closed = true; incoming.cancel() }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    private fun CoroutineScope.collect(session: SpeechSession): Channel<SpeechEvent> = Channel<SpeechEvent>(32).also { output ->
        launch { session.events.filter { it !is SpeechEvent.Level }.collect { output.send(it) }; output.close() }
    }

    private suspend fun Socket.text() = (sent.receive() as Out.Text).text
    private suspend fun Socket.audio() = (sent.receive() as Out.Audio).pcm

    @Test fun uploadsWhileHeldAndGivesFinalOnlyAfterStopped() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this)
        val events = collect(session)
        assertTrue(session.live)
        // 边说边传:按住多久都行
        assertTrue(session.unlimited)
        assertEquals("START", socket.text())
        // 服务端确认前录到的音频先留着
        repeat(2) { capture.chunks.send(ShortArray(800) { 7 }) }
        yield()
        assertTrue(socket.sent.tryReceive().isFailure)
        socket.incoming.send("""{"event": "started"}""")
        val first = socket.audio()
        assertEquals(LiveSpeech.PACKET_SAMPLES * 2, first.size)
        assertEquals(7.toByte(), first[0]); assertEquals(0.toByte(), first[1]) // 小端
        socket.incoming.send("""{"sentences": [], "partial": "你好", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("", "你好"), events.receive())
        // 一样的快照不重复发
        socket.incoming.send("""{"sentences": [], "partial": "你好", "is_final": false}""")
        socket.incoming.send("""{"sentences": [{"text": "你好。"}], "partial": "打开", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("你好。", "打开"), events.receive())
        capture.chunks.send(shortArrayOf(1, 2, 3))
        session.release(); session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        // 不足一包的尾巴照样送出去,然后是收尾消息
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), socket.audio())
        assertEquals("STOP", socket.text())
        socket.incoming.send("""{"sentences": [{"text": "你好。"}, {"text": "打开微信。"}], "partial": "", "is_final": true}""")
        assertEquals(SpeechEvent.Partial("你好。打开微信。", ""), events.receive())
        assertTrue(events.tryReceive().isFailure) // 终结果到了还要等 stopped
        socket.incoming.send("""{"event": "stopped"}""")
        assertEquals(SpeechEvent.Final("你好。打开微信。"), events.receive())
        assertTrue(events.receiveCatching().isClosed)
        yield()
        assertTrue(socket.closed); assertTrue(capture.cancelled)
    }

    // 断开但没等到结束:失败,不把没收完的中间结果当最终文字(交出去的字会发给模型)
    @Test fun disconnectBeforeEndFails() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this)
        val events = collect(session)
        socket.text(); socket.incoming.send("""{"event": "started"}""")
        socket.incoming.send("""{"sentences": [], "partial": "一半", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("", "一半"), events.receive())
        socket.incoming.close()
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
    }

    @Test fun finalWaitTimesOut() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this, finalTimeoutMs = 30)
        val events = collect(session)
        socket.text(); socket.incoming.send("""{"event": "started"}""")
        session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        assertEquals("STOP", socket.text())
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
        yield()
        assertTrue(socket.closed)
    }

    @Test fun serviceErrorsAndAuthAreReported() = test {
        val socket = Socket()
        val session = LiveSpeech(Capture(), BailianProtocol("fun-asr-realtime"), { socket }, this)
        val events = collect(session)
        socket.text()
        socket.incoming.send("""{"header":{"event":"task-failed","error_code":"InvalidApiKey","error_message":"x"},"payload":{}}""")
        val failed = events.receive() as SpeechEvent.Failed
        assertTrue((failed.why as SpeechFailure.Provider).error is ModelError.Auth)

        val handshake = LiveSpeech(Capture(), FunAsrProtocol(), { throw ModelError.Auth() }, this)
        val second = collect(handshake).receive() as SpeechEvent.Failed
        assertTrue((second.why as SpeechFailure.Provider).error is ModelError.Auth)
    }

    @Test fun micThatDoesNotStartFailsAtOnce() = test {
        val events = collect(LiveSpeech(Capture(starts = false), FunAsrProtocol(), { error("not called") }, this))
        assertEquals(SpeechEvent.Failed(SpeechFailure.MicUnavailable), events.receive())
    }

    @Test fun cancelDropsLateResults() = test {
        val capture = Capture(); val socket = Socket(); val ready = CompletableDeferred<Unit>()
        val session = LiveSpeech(capture, FunAsrProtocol(), { ready.await(); socket }, this)
        val events = collect(session)
        session.cancel(); session.cancel()
        ready.complete(Unit)
        assertTrue(events.receiveCatching().isClosed)
        assertTrue(capture.cancelled)
    }

    @Test fun bailianPacketsArePacedAndFunAsrIsNot() {
        assertEquals(50L, BailianProtocol("fun-asr-realtime").packetGapMs)
        assertEquals(0L, FunAsrProtocol().packetGapMs)
    }

    @Test fun probePassesOnStartAndSendsClosing() = test {
        val socket = Socket()
        val done = launch { probeLiveAsr(FunAsrProtocol(), { socket }) }
        assertEquals("START", socket.text())
        socket.incoming.send("""{"event": "started"}""")
        assertEquals("STOP", socket.text())
        done.join()
        assertTrue(socket.closed)
    }

    @Test fun probeReportsRefusalAndEarlyClose() {
        runBlocking {
            val refused = Socket()
            refused.incoming.send("""{"header":{"event":"task-failed","error_code":"Arrearage"},"payload":{}}""")
            val e = runCatching { probeLiveAsr(BailianProtocol("fun-asr-realtime"), { refused }) }.exceptionOrNull()
            assertTrue((e as LiveAsrError).auth)
            val closed = Socket().also { it.incoming.close() }
            assertTrue(runCatching { probeLiveAsr(FunAsrProtocol(), { closed }) }.exceptionOrNull() is IOException)
        }
        assertThrows(Exception::class.java) { runBlocking { probeLiveAsr(FunAsrProtocol(), { Socket() }, timeoutMs = 20) } }
    }

    @Test fun joinAddsSpacesOnlyBetweenLatinWords() {
        assertEquals("你好世界", joinAsr(listOf("你好", "世界")))
        assertEquals("hello world", joinAsr(listOf("hello", "", "world")))
        assertEquals("hello.World", joinAsr(listOf("hello.", "World")))
        assertEquals("", joinAsr(emptyList()))
    }
}
