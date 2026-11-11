package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.VolcRealtimeProtocol.string
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class VolcRealtimeCredentials(apiKey: String = "", appId: String = "", accessKey: String = "") {
    private val apiKey = apiKey.trim()
    private val appId = appId.trim()
    private val accessKey = accessKey.trim()
    private val usesApiKey = this.apiKey.isNotEmpty()

    val valid: Boolean get() = if (usesApiKey) isHeaderValue(apiKey) else isHeaderValue(appId) && isHeaderValue(accessKey)

    internal fun applyHeaders(builder: Request.Builder) {
        if (!valid) throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
        if (usesApiKey) builder.header("X-Api-Key", apiKey)
        else builder.header("X-Api-App-Id", appId)
            .header("X-Api-Access-Key", accessKey)
            .header("X-Api-Resource-Id", VolcRealtimeProtocol.RESOURCE)
            .header("X-Api-App-Key", VolcRealtimeProtocol.LEGACY_APP_KEY)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
    }

    private fun isHeaderValue(value: String): Boolean = value.isNotEmpty() && value.length <= 1024 &&
        value.all { it in '!'..'~' }

    override fun toString(): String = "VolcRealtimeCredentials(redacted)"
}

/** The error text intentionally excludes credentials, response bodies, and transcripts. */
class VolcRealtimeException internal constructor(val kind: Kind, val code: Long? = null) :
    IOException("Volcengine realtime: ${kind.name.lowercase()}${code?.let { " ($it)" }.orEmpty()}") {
    enum class Kind { AUTH, RATE_LIMITED, TIMEOUT, NETWORK, PROTOCOL, PROVIDER, BACKPRESSURE }
}

sealed interface VolcRealtimeEvent {
    /** PCM s16le, mono, 24 kHz. */
    data class Audio(val pcm: ByteArray) : VolcRealtimeEvent
    data object UserSpeaking : VolcRealtimeEvent
    data class UserTranscript(
        val text: String,
        val isFinal: Boolean,
        /** Only a complete, non-blank provider final can authorize call control. */
        val isCompleteFinal: Boolean = isFinal,
    ) : VolcRealtimeEvent
    data object UserSpeechEnded : VolcRealtimeEvent
    /** Provider observations only; these do not start, stop, or retry a turn. */
    data object InputAudioCommitted : VolcRealtimeEvent
    data object UserTranscriptionEmpty : VolcRealtimeEvent
    data object UserTranscriptionFailed : VolcRealtimeEvent
    data object AssistantAudioStarted : VolcRealtimeEvent
    data object AssistantAudioEnded : VolcRealtimeEvent
    data object AssistantInterrupted : VolcRealtimeEvent
    data class AssistantTranscript(val delta: String) : VolcRealtimeEvent
    data object AssistantTextEnded : VolcRealtimeEvent
    data class Failed(val error: VolcRealtimeException) : VolcRealtimeEvent
    data object Closed : VolcRealtimeEvent
}

/** Opens one session and never retries it automatically. */
class VolcRealtimeClient internal constructor(
    http: OkHttpClient,
    private val endpoint: String,
    private val connectTimeoutMs: Long = 10_000,
    private val closeTimeoutMs: Long = 3_000,
) {
    constructor() : this(defaultHttp, VolcRealtimeProtocol.ENDPOINT)

    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    /** Returns after the provider acknowledges session.create, not just the HTTP upgrade. */
    suspend fun connect(
        credentials: VolcRealtimeCredentials,
        config: VolcRealtimeConfig = VolcRealtimeConfig(),
    ): VolcRealtimeSession {
        val request = Request.Builder().url(endpoint).apply { credentials.applyHeaders(this) }.build()
        val session = VolcRealtimeSession(config, closeTimeoutMs)
        try {
            session.attach(client.newWebSocket(request, session.listener))
            return withTimeoutOrNull(connectTimeoutMs) { session.awaitReady(); session }
                ?: throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT)
        } catch (error: CancellationException) {
            session.abort(VolcRealtimeException(VolcRealtimeException.Kind.NETWORK))
            throw error
        } catch (error: Exception) {
            val safe = (error as? VolcRealtimeException) ?: VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
            session.abort(safe)
            throw safe
        }
    }

    companion object {
        const val INPUT_SAMPLE_RATE = VolcRealtimeProtocol.INPUT_RATE
        const val OUTPUT_SAMPLE_RATE = VolcRealtimeProtocol.OUTPUT_RATE
        const val INPUT_CHUNK_BYTES = VolcRealtimeProtocol.CHUNK_BYTES

        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}

class VolcRealtimeSession internal constructor(
    private val config: VolcRealtimeConfig,
    private val closeTimeoutMs: Long,
) : AutoCloseable {
    private enum class Phase { CONNECTING, CREATING, ACTIVE, ENDING_SESSION, ENDING_SOCKET, ENDED }
    private var phase = Phase.CONNECTING
    private var socket: WebSocket? = null
    private var closeWatchdog: Job? = null
    private var sequence = 0L
    private var muted = false
    private var currentUserItem: String? = null
    private var userText = StringBuilder()
    private var audioActive = false
    private var audioId: String? = null
    private var interruptedAudioId: String? = null
    private var waitForAudioStart = false
    private val ready = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val output = Channel<VolcRealtimeEvent>(32)
    private val collecting = AtomicBoolean(false)
    @Volatile private var discardPending = false
    private var terminal: VolcRealtimeEvent = VolcRealtimeEvent.Closed

    /** Single consumer. Keep the collector responsive; overflowing the buffer ends the session. */
    val events: Flow<VolcRealtimeEvent> = flow {
        check(collecting.compareAndSet(false, true)) { "Realtime events have one consumer" }
        for (event in output) if (!discardPending) emit(event)
        emit(terminal)
    }

    internal val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = opened(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) = received(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = invalidFrame()
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closing(webSocket, code)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socketClosed(code)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.close()
            val kind = when (code) {
                401, 403 -> VolcRealtimeException.Kind.AUTH
                429 -> VolcRealtimeException.Kind.RATE_LIMITED
                null -> VolcRealtimeException.Kind.NETWORK
                else -> VolcRealtimeException.Kind.PROVIDER
            }
            abort(VolcRealtimeException(kind, code?.toLong()))
        }
    }

    internal suspend fun awaitReady() = ready.await()

    @Synchronized internal fun attach(webSocket: WebSocket) {
        if (phase == Phase.ENDED) webSocket.cancel() else socket = webSocket
    }

    @Synchronized private fun opened(webSocket: WebSocket) {
        if (phase != Phase.CONNECTING) { webSocket.cancel(); return }
        socket = webSocket
        phase = Phase.CREATING
        send(VolcRealtimeProtocol.create(nextId(), config))
    }

    /** Accepts raw 16 kHz mono PCM16 LE in real time, preferably exactly 640 bytes per 20 ms. */
    @Synchronized fun sendPcm16(bytes: ByteArray, byteCount: Int = bytes.size): Boolean {
        if (phase != Phase.ACTIVE || muted || byteCount !in 2..VolcRealtimeProtocol.MAX_INPUT_BYTES ||
            byteCount > bytes.size || byteCount % 2 != 0) return false
        return send(VolcRealtimeProtocol.audio(nextId(), bytes, byteCount))
    }

    /** Call after phone audio is ready; no greeting is sent by connect(). */
    @Synchronized fun sendGreeting(text: String): Boolean {
        if (phase != Phase.ACTIVE || text.isBlank() || text.length > 1024) return false
        return send(VolcRealtimeProtocol.greeting(nextId(), text))
    }

    @Synchronized fun setMuted(muted: Boolean): Boolean {
        if (phase != Phase.ACTIVE) return false
        if (this.muted == muted) return true
        return send(VolcRealtimeProtocol.mute(nextId(), muted)).also { if (it) this.muted = muted }
    }

    /** Stops local media immediately; waits briefly for session.closed before closing the socket. */
    @Synchronized override fun close() {
        if (phase == Phase.ENDED) return
        discardPending = true
        when (phase) {
            Phase.ACTIVE, Phase.CREATING -> {
                phase = Phase.ENDING_SESSION
                if (send(VolcRealtimeProtocol.close(nextId()))) armCloseWatchdog()
            }
            Phase.CONNECTING -> end(null, cancelSocket = true)
            Phase.ENDING_SESSION, Phase.ENDING_SOCKET, Phase.ENDED -> Unit
        }
    }

    suspend fun awaitClosed() = closed.await()

    @Synchronized internal fun abort(error: VolcRealtimeException) {
        end(if (discardPending) null else error, cancelSocket = true)
    }

    @Synchronized private fun invalidFrame() {
        if (phase == Phase.CREATING || phase == Phase.ACTIVE) abort(VolcRealtimeException(VolcRealtimeException.Kind.PROTOCOL))
    }

    @Synchronized private fun received(text: String) {
        if (phase == Phase.ENDED || phase == Phase.ENDING_SOCKET) return
        val frame = VolcRealtimeProtocol.parse(text)
        if (frame == null) { invalidFrame(); return }
        val type = frame.string("type")
        when (type) {
            VolcRealtimeProtocol.SESSION_CREATED -> if (phase == Phase.CREATING) {
                phase = Phase.ACTIVE
                ready.complete(Unit)
            }
            VolcRealtimeProtocol.SESSION_CLOSED -> {
                if (phase == Phase.CREATING) {
                    abort(VolcRealtimeException(VolcRealtimeException.Kind.PROVIDER))
                } else {
                    phase = Phase.ENDING_SOCKET
                    armCloseWatchdog()
                    if (socket?.close(1000, null) != true) end(null, cancelSocket = true)
                }
            }
            VolcRealtimeProtocol.ERROR -> {
                val code = VolcRealtimeProtocol.errorCode(frame)
                val kind = when (code) {
                    401L, 403L -> VolcRealtimeException.Kind.AUTH
                    429L -> VolcRealtimeException.Kind.RATE_LIMITED
                    else -> VolcRealtimeException.Kind.PROVIDER
                }
                abort(VolcRealtimeException(kind, code))
            }
        }
        if (phase != Phase.ACTIVE) return
        val responseId = frame.string("response_id")?.takeIf { it.isNotBlank() }
        when (type) {
            VolcRealtimeProtocol.INPUT_AUDIO_COMMITTED -> publish(VolcRealtimeEvent.InputAudioCommitted)
            VolcRealtimeProtocol.USER_STARTED -> {
                currentUserItem = frame.string("item_id")?.takeIf { it.isNotEmpty() }
                userText = StringBuilder()
                interruptAudio()
                publish(VolcRealtimeEvent.UserSpeaking)
            }
            VolcRealtimeProtocol.USER_DELTA -> {
                val itemId = frame.string("item_id")?.takeIf { it.isNotEmpty() }
                if (itemId != null && itemId != currentUserItem) {
                    currentUserItem = itemId
                    userText = StringBuilder()
                }
                val delta = frame.string("delta") ?: return
                val remaining = MAX_TRANSCRIPT_CHARS - userText.length
                if (remaining > 0 && delta.isNotEmpty()) {
                    userText.append(delta, 0, minOf(remaining, delta.length))
                    publish(VolcRealtimeEvent.UserTranscript(userText.toString(), isFinal = false))
                }
            }
            VolcRealtimeProtocol.USER_COMPLETED -> {
                val explicitFinal = (frame.string("transcript") ?: frame.string("text"))?.takeIf { it.isNotBlank() }
                val transcript = explicitFinal ?: userText.toString()
                currentUserItem = null
                userText = StringBuilder()
                if (transcript.isNotBlank()) publish(VolcRealtimeEvent.UserTranscript(
                    transcript.take(MAX_TRANSCRIPT_CHARS),
                    isFinal = true,
                    isCompleteFinal = explicitFinal != null && explicitFinal.length <= MAX_TRANSCRIPT_CHARS,
                ))
                publish(VolcRealtimeEvent.UserSpeechEnded)
                if (transcript.isBlank()) publish(VolcRealtimeEvent.UserTranscriptionEmpty)
            }
            VolcRealtimeProtocol.USER_FAILED -> {
                currentUserItem = null
                userText = StringBuilder()
                publish(VolcRealtimeEvent.UserSpeechEnded)
                publish(VolcRealtimeEvent.UserTranscriptionFailed)
            }
            VolcRealtimeProtocol.TEXT_DELTA -> frame.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                publish(VolcRealtimeEvent.AssistantTranscript(it))
            }
            VolcRealtimeProtocol.TEXT_DONE -> publish(VolcRealtimeEvent.AssistantTextEnded)
            VolcRealtimeProtocol.AUDIO_STARTED -> if (responseId == null || responseId != interruptedAudioId) {
                waitForAudioStart = false
                if (!audioActive || (responseId != null && responseId != audioId)) {
                    audioId = responseId
                    audioActive = true
                    publish(VolcRealtimeEvent.AssistantAudioStarted)
                }
            }
            VolcRealtimeProtocol.AUDIO_DELTA -> {
                if (waitForAudioStart || (responseId != null && responseId == interruptedAudioId)) return
                val pcm = VolcRealtimeProtocol.pcm(frame) ?: run { invalidFrame(); return }
                if (pcm.isEmpty()) return
                if (!audioActive) {
                    audioActive = true
                    publish(VolcRealtimeEvent.AssistantAudioStarted)
                }
                if (responseId != null) audioId = responseId
                publish(VolcRealtimeEvent.Audio(pcm))
            }
            VolcRealtimeProtocol.AUDIO_DONE -> if (audioActive && (responseId == null || responseId == audioId || audioId == null)) {
                audioActive = false
                audioId = null
                publish(VolcRealtimeEvent.AssistantAudioEnded)
            }
            VolcRealtimeProtocol.CANCELED -> {
                // A late cancellation for an older response must not flush the replacement.
                val currentId = audioId
                if (responseId != null && (responseId == interruptedAudioId ||
                        (currentId != null && responseId != currentId))) return
                interruptAudio()
                publish(VolcRealtimeEvent.AssistantInterrupted)
            }
        }
    }

    private fun interruptAudio() {
        if (!audioActive) return
        interruptedAudioId = audioId
        audioActive = false
        audioId = null
        waitForAudioStart = true
    }

    @Synchronized private fun closing(webSocket: WebSocket, code: Int) {
        if (phase == Phase.ENDED) return
        if (phase == Phase.CONNECTING || phase == Phase.CREATING || (!discardPending && code != 1000)) {
            abort(VolcRealtimeException(VolcRealtimeException.Kind.NETWORK, code.toLong()))
            return
        }
        phase = Phase.ENDING_SOCKET
        armCloseWatchdog()
        webSocket.close(1000, null)
    }

    @Synchronized private fun socketClosed(code: Int) {
        val expected = phase == Phase.ENDING_SOCKET || phase == Phase.ENDING_SESSION || (phase == Phase.ACTIVE && code == 1000)
        end(if (expected) null else VolcRealtimeException(VolcRealtimeException.Kind.NETWORK, code.toLong()))
    }

    private fun publish(event: VolcRealtimeEvent) {
        if (phase == Phase.ACTIVE && output.trySend(event).isFailure) {
            // Do not replay stale audio if the consumer has fallen behind.
            end(VolcRealtimeException(VolcRealtimeException.Kind.BACKPRESSURE), cancelSocket = true, dropMedia = true)
        }
    }

    private fun send(frame: String): Boolean {
        val connection = socket
        if (connection != null && connection.queueSize() + frame.length <= MAX_SEND_QUEUE_BYTES && connection.send(frame)) return true
        val kind = if (connection != null && connection.queueSize() + frame.length > MAX_SEND_QUEUE_BYTES) {
            VolcRealtimeException.Kind.BACKPRESSURE
        } else VolcRealtimeException.Kind.NETWORK
        abort(VolcRealtimeException(kind))
        return false
    }

    private fun nextId() = "mika_${++sequence}"

    private fun armCloseWatchdog() {
        if (closeWatchdog != null) return
        closeWatchdog = cleanupScope.launch {
            delay(closeTimeoutMs)
            synchronized(this@VolcRealtimeSession) { end(null, cancelSocket = true) }
        }
    }

    private fun end(error: VolcRealtimeException?, cancelSocket: Boolean = false, dropMedia: Boolean = false) {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        closeWatchdog?.cancel()
        val connection = socket
        socket = null
        if (cancelSocket) connection?.cancel()
        if (dropMedia) discardPending = true
        terminal = error?.let(VolcRealtimeEvent::Failed) ?: VolcRealtimeEvent.Closed
        output.close()
        ready.completeExceptionally(error ?: VolcRealtimeException(VolcRealtimeException.Kind.NETWORK))
        closed.complete(Unit)
    }

    private companion object {
        const val MAX_SEND_QUEUE_BYTES = 32 * 1024 // Well below OkHttp's 16 MiB default WebSocket queue.
        const val MAX_TRANSCRIPT_CHARS = 16_384
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
