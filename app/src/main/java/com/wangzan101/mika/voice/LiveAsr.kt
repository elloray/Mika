package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * 文字控制帧 + 二进制 PCM 音频的流式识别(自建 Fun-ASR、百炼实时识别)。
 * 各家只在 [LiveAsrProtocol] 里不同:开场发什么、收尾发什么、怎么读服务端的消息;连接、上传、等终结果都在这里。
 */
internal interface LiveAsrProtocol {
    /** 连上后先发的文字消息;发完等 [LiveAsrUpdate.Started] 才开始送音频。 */
    fun opening(): List<String>

    /** 录音结束、最后一包音频送完后发的文字消息;之后等 [LiveAsrUpdate.Ended]。 */
    fun closing(): List<String>

    /** 两包音频之间至少隔多少毫秒;0 表示来多少送多少(连上前攒下的音频尽快追上)。 */
    val packetGapMs: Long get() = 0

    /** 读一条服务端消息(二进制消息按 UTF-8 文字读)。服务端报错时抛 [LiveAsrError]。 */
    fun read(message: String): LiveAsrUpdate
}

internal sealed interface LiveAsrUpdate {
    /** 服务端准备好收音频了。 */
    data object Started : LiveAsrUpdate

    /** 到此刻的全文:[stable] 是已经定下的句子,[tail] 是还在变的那句。每次都是整份替换,不是增量。 */
    data class Text(val stable: String, val tail: String) : LiveAsrUpdate

    /** 这次识别结束;[text] 为 null 时用最后一次 [Text] 的全文。 */
    data class Ended(val text: String? = null) : LiveAsrUpdate

    /** 心跳、确认之类,不改文字。 */
    data object None : LiveAsrUpdate
}

/** 服务端报的错:只带错误码,不带服务端原话(可能回显凭据或说的话)。[auth] 是鉴权、额度一类,界面指去设置。 */
internal class LiveAsrError(val code: String, val auth: Boolean = false) : IOException("ASR error $code")

internal interface LiveAsrSocket {
    /** 服务端的消息;连接断了就关上(异常断开时带着原因)。 */
    val incoming: ReceiveChannel<String>
    fun sendText(text: String)
    fun sendAudio(pcm: ByteArray)
    fun close()
}

/** 独立的 OkHttp WebSocket:不跟随重定向(凭据只发往填的那个地址),读不设超时(说话时可能很久没有结果)。 */
internal class LiveAsrTransport(private val client: OkHttpClient = http) {
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap()): LiveAsrSocket {
        val ready = CompletableDeferred<Unit>()
        val messages = Channel<String>(64)
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { offer(webSocket, text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { offer(webSocket, bytes.utf8()) }
            private fun offer(webSocket: WebSocket, text: String) {
                if (text.length > MAX_MESSAGE || messages.trySend(text).isFailure) {
                    messages.close(IOException("ASR response buffer exceeded")); webSocket.cancel()
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.close(); webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = when (response?.code) {
                    401, 403 -> ModelError.Auth()
                    429 -> ModelError.RateLimited(null)
                    else -> ModelError.Network()
                }
                response?.close()
                ready.completeExceptionally(error)
                messages.close(error)
            }
        })
        try { ready.await() } catch (e: Throwable) { socket.cancel(); messages.cancel(); throw e }
        return object : LiveAsrSocket {
            override val incoming = messages
            override fun sendText(text: String) { if (!socket.send(text)) throw IOException("ASR send failed") }
            override fun sendAudio(pcm: ByteArray) {
                if (socket.queueSize() > MAX_QUEUE || !socket.send(pcm.toByteString())) throw IOException("ASR send failed")
            }
            override fun close() { socket.cancel(); messages.cancel() }
        }
    }

    companion object {
        private const val MAX_MESSAGE = 1024 * 1024
        private const val MAX_QUEUE = 256 * 1024L
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}

/**
 * 边说边转时告诉服务说的是哪种语言;[Auto] 不指定,由服务自己判断。存的是 [code](也是百炼 `language_hints` 用的语言代码),
 * 只能往后加。自建 Fun-ASR 服务的提示词按中文语言名写(「语音转写成中文」),所以另有 [funAsrName]。
 * 服务能不能识别某种语言取决于它跑的模型(Fun-ASR-Nano 只有中、英、日,多语种版本更多)。
 */
enum class AsrLanguage(val code: String, val funAsrName: String) {
    Auto("", ""),
    Chinese("zh", "中文"),
    English("en", "英文"),
    Japanese("ja", "日文"),
    Cantonese("yue", "粤语"),
    Korean("ko", "韩文"),
    ;

    companion object {
        /** 读不出来、不认识的一律当 [Auto],不让整条选择失效。 */
        fun of(code: String?): AsrLanguage = entries.firstOrNull { it.code == code } ?: Auto
    }
}

/** 拼文字:前后两段都是拉丁字母或数字时中间补一个空格,中文直接接上。 */
internal fun joinAsr(parts: List<String>): String = parts.filter(String::isNotEmpty).fold("") { acc, s ->
    if (acc.isNotEmpty() && acc.last().isLetterOrDigit() && acc.last().code < 128 && s.first().isLetterOrDigit() && s.first().code < 128) "$acc $s" else acc + s
}

/** 小端 PCM16。 */
internal fun pcmBytes(pcm: ShortArray, size: Int = pcm.size): ByteArray {
    val data = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until size) data.putShort(pcm[i])
    return data.array()
}

/**
 * 一次按住说话:录音和上传、收结果同时进行;松开后送完剩下的音频和收尾消息,等服务端说结束才给 Final。
 * 断开、超时都算失败,不把没收完的中间结果当最终文字交出去(交出去的字会接着发给模型)。
 * 按住多久都行([unlimited]):只有连接、等开始、等终结果各有时限,整次会话不设上限。
 */
internal class LiveSpeech(
    private val recorder: StreamingRecorder,
    private val protocol: LiveAsrProtocol,
    private val connect: suspend () -> LiveAsrSocket,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 10_000,
    private val packetSamples: Int = PACKET_SAMPLES,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    // 边说边传、不攒整段录音:按住多久都行
    override val unlimited = true
    private var work: Job? = null
    private var levels: Job? = null
    private var stopping: Job? = null
    private var released = false

    init {
        if (!recorder.start()) out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
            work = scope.launch { runSession() }
        }
    }

    private suspend fun runSession() {
        var socket: LiveAsrSocket? = null
        try {
            run {
                val connection = withTimeout(connectTimeoutMs) { connect() }
                socket = connection
                protocol.opening().forEach(connection::sendText)
                var text = LiveAsrUpdate.Text("", "")
                // 服务端确认前不送音频;这期间的录音留在录音器的队列里
                withTimeout(connectTimeoutMs) {
                    while (true) {
                        when (val u = protocol.read(connection.incoming.receive())) {
                            LiveAsrUpdate.Started -> break
                            is LiveAsrUpdate.Text -> text = u
                            is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                            LiveAsrUpdate.None -> Unit
                        }
                    }
                }
                coroutineScope {
                    val result = async {
                        for (message in connection.incoming) {
                            when (val u = protocol.read(message)) {
                                is LiveAsrUpdate.Text -> if (u != text) {
                                    text = u
                                    if (u.stable.isNotEmpty() || u.tail.isNotEmpty()) out.send(SpeechEvent.Partial(u.stable, u.tail))
                                }
                                is LiveAsrUpdate.Ended -> return@async (u.text ?: joinAsr(listOf(text.stable, text.tail))).trim()
                                LiveAsrUpdate.Started, LiveAsrUpdate.None -> Unit
                            }
                        }
                        throw IOException("ASR closed before final result")
                    }
                    val audioEnded = CompletableDeferred<Unit>()
                    val sender = launch {
                        val buffer = ShortArray(packetSamples)
                        var size = 0
                        var next = 0L
                        suspend fun send(count: Int) {
                            val wait = next - System.nanoTime()
                            if (wait > 0) delay((wait + 999_999) / 1_000_000)
                            connection.sendAudio(pcmBytes(buffer, count))
                            next = System.nanoTime() + protocol.packetGapMs * 1_000_000
                        }
                        for (chunk in recorder.chunks) {
                            var offset = 0
                            while (offset < chunk.size) {
                                val count = minOf(buffer.size - size, chunk.size - offset)
                                chunk.copyInto(buffer, size, offset, offset + count)
                                size += count; offset += count
                                if (size == buffer.size) { send(size); size = 0 }
                            }
                        }
                        if (size > 0) send(size)
                        protocol.closing().forEach(connection::sendText)
                        audioEnded.complete(Unit)
                    }
                    try {
                        val finalText = select<String> {
                            result.onAwait { it }
                            audioEnded.onAwait { withTimeout(finalTimeoutMs) { result.await() } }
                        }
                        out.finish(SpeechEvent.Final(finalText))
                    } finally { sender.cancelAndJoin() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LiveAsrError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(if (e.auth) ModelError.Auth() else ModelError.BadResponse("ASR error ${e.code}"))))
        } catch (e: ModelError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(e)))
        } catch (e: IOException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (_: Exception) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse("Invalid ASR response"))))
        } finally {
            socket?.close()
            levels?.cancel()
            recorder.cancel()
        }
    }

    @Synchronized override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        out.send(SpeechEvent.Converting)
        levels?.cancel()
        stopping = scope.launch { recorder.stop() }
    }

    @Synchronized override fun cancel() {
        if (!out.cancel()) return
        work?.cancel(); levels?.cancel(); stopping?.cancel()
        recorder.cancel()
    }

    companion object {
        /** 100 ms 一包(16 kHz 单声道)。 */
        const val PACKET_SAMPLES = 1600
    }
}

/**
 * 不说话只走开场:连得上、服务端回了「开始」(地址、鉴权、模型都认)就算通,随后发收尾消息、断开。
 * 设置页保存前用,不送音频,不产生识别时长;不等服务端对空识别的收尾回应(各家对没有音频的识别怎么收尾不一定)。
 * 失败抛 [LiveAsrError]、[ModelError] 或 IOException。
 */
internal suspend fun probeLiveAsr(protocol: LiveAsrProtocol, connect: suspend () -> LiveAsrSocket, timeoutMs: Long = 8_000) {
    withTimeout(timeoutMs) {
        val socket = connect()
        try {
            protocol.opening().forEach(socket::sendText)
            for (message in socket.incoming) {
                when (protocol.read(message)) {
                    LiveAsrUpdate.Started -> {
                        runCatching { protocol.closing().forEach(socket::sendText) }
                        return@withTimeout
                    }
                    is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                    else -> Unit
                }
            }
            throw IOException("ASR closed during probe")
        } finally { socket.close() }
    }
}
