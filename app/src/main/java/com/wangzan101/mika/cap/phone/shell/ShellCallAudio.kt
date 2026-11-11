package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs

/** Cellular audio only. Control stays on the authenticated helper Binder; PCM uses anonymous pipes. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
internal class ShellCallAudio(private val context: Context) {
    private val manager: AudioManager by lazy {
        AudioManager::class.java.getConstructor(Context::class.java).newInstance(context)
    }
    private var active: Session? = null
    private var lastOwner: IBinder? = null
    private var lastStats: Bundle? = null

    /** Looks at platform contracts and routing topology without opening an audio stream. */
    fun probe(mode: Int): Bundle = try {
        check(Process.myUid() == ShellContext.SHELL_UID) { "Call audio must run as Android shell." }
        require(mode in AUTO..REDIRECT) { "Unknown cellular audio mode." }
        val rx = telephonyDevice(AudioManager.GET_DEVICES_INPUTS) != null
        val tx = telephonyDevice(AudioManager.GET_DEVICES_OUTPUTS) != null
        val canMute = granted("MODIFY_AUDIO_SETTINGS")
        val directMissing = listOf("RECORD_AUDIO", "CAPTURE_AUDIO_OUTPUT", "MODIFY_PHONE_STATE")
            .filterNot(::granted)
        val direct = canMute && directMissing.isEmpty() && rx && tx
        val redirectDetail = runCatching {
            check(Build.VERSION.SDK_INT >= 33) { "PSTN redirection requires Android 13+." }
            check(granted("CALL_AUDIO_INTERCEPTION")) { "Missing CALL_AUDIO_INTERCEPTION." }
            RedirectApi() // Resolve the hidden methods without instantiating a builder or stream.
            val available = AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
                .invoke(manager) == true
            check(available) { "Android audio policy does not expose PSTN interception." }
        }.exceptionOrNull()?.let(::describe)
        val redirect = canMute && redirectDetail == null
        val ready = candidates(mode, direct, redirect).isNotEmpty()
        Bundle().apply {
            putBoolean("ready", ready)
            putBoolean("verified", false)
            putBoolean("direct", direct)
            putBoolean("redirect", redirect)
            putBoolean("telephonyRx", rx)
            putBoolean("telephonyTx", tx)
            putInt("uid", Process.myUid())
            putInt("sdk", Build.VERSION.SDK_INT)
            putString("reason", when {
                ready -> ""
                !canMute -> "Missing MODIFY_AUDIO_SETTINGS."
                mode == REDIRECT -> redirectDetail
                mode == DIRECT && directMissing.isNotEmpty() -> "Missing ${directMissing.joinToString()}."
                mode == DIRECT -> "Telephony RX/TX devices unavailable."
                else -> "Direct: " + (if (directMissing.isEmpty()) "telephony RX/TX unavailable" else
                    "missing ${directMissing.joinToString()}") + "; redirect: $redirectDetail"
            })
        }
    } catch (e: Exception) {
        failure(describe(e))
    }

    @Synchronized
    fun open(mode: Int, owner: IBinder): Bundle {
        if (active != null) return failure("A call audio session is already open.")
        if (!owner.isBinderAlive) return failure("Call audio owner is gone.")
        val capability = probe(mode)
        if (!capability.getBoolean("ready")) return rejected(owner, capability)
        if (!inPstnCall()) return rejected(owner, failure("Cellular call audio is not active."))
        val modes = candidates(mode, capability.getBoolean("direct"), capability.getBoolean("redirect"))
        val errors = ArrayList<String>(modes.size)
        for (backend in modes) {
            try {
                return start(backend, owner)
            } catch (e: Exception) {
                errors.add("${if (backend == DIRECT) "Direct" else "Redirect"}: ${describe(e)}")
            }
            if (!owner.isBinderAlive || !inPstnCall()) break
        }
        return rejected(owner, failure(errors.joinToString("; ")))
    }

    private fun rejected(owner: IBinder, result: Bundle): Bundle {
        result.putBoolean("active", false)
        result.putBoolean("microphoneRestored", true)
        lastOwner = owner
        lastStats = Bundle(result)
        return result
    }

    private fun start(backend: Int, owner: IBinder): Bundle {
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var fromCall: Array<ParcelFileDescriptor>? = null
        var toCall: Array<ParcelFileDescriptor>? = null
        var session: Session? = null
        try {
            if (backend == DIRECT) {
                record = recordBuilder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK).build()
                track = trackBuilder(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
                val tx = checkNotNull(telephonyDevice(AudioManager.GET_DEVICES_OUTPUTS)) {
                    "Telephony uplink device is no longer available."
                }
                check(track.setPreferredDevice(tx)) { "Telephony uplink routing was rejected." }
            } else {
                // Explicitly attribute the builders to shell. The framework convenience
                // helpers create context-free builders, whose attribution depends on bootstrap.
                val api = RedirectApi()
                record = api.record(recordBuilder())
                track = api.track(::trackBuilder)
            }
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Downlink audio could not be initialized." }
            check(record.audioSource == MediaRecorder.AudioSource.VOICE_DOWNLINK) {
                "The recorder is not using the cellular downlink source."
            }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Uplink audio could not be initialized." }
            fromCall = ParcelFileDescriptor.createPipe()
            toCall = ParcelFileDescriptor.createPipe()
            nonBlocking(fromCall[1].fileDescriptor)
            nonBlocking(toCall[0].fileDescriptor)
            val created = Session(owner, backend, record, track, fromCall[1], toCall[0])
            session = created
            owner.linkToDeath(created.death, 0)
            check(inPstnCall() && owner.isBinderAlive) { "Cellular call or its owner ended." }
            created.requireCallMicrophoneMute()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Downlink did not start." }
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Uplink did not start." }
            created.establishRoute()
            active = created
            lastOwner = null
            lastStats = null
            created.launch()
            // In a remote Binder call, the Stub serializes this Bundle; Bundle does not
            // transfer PFD ownership on its own. Close these server copies after parceling.
            return Bundle().apply {
                putBoolean("ready", true)
                putBoolean("verified", false)
                putString("reason", "")
                putInt("mode", backend)
                putInt("captureRate", CAPTURE_RATE)
                putInt("playRate", PLAY_RATE)
                putParcelable("downlink", fromCall[0])
                putParcelable("uplink", toCall[1])
            }
        } catch (e: Exception) {
            if (active === session) active = null
            if (session != null) {
                session.release()
                lastOwner = owner
                lastStats = session.snapshot(false, describe(e))
            } else {
                runCatching { record?.release() }
                runCatching { track?.release() }
            }
            fromCall?.forEach { runCatching { it.close() } }
            toCall?.forEach { runCatching { it.close() } }
            throw e
        }
    }

    @Synchronized
    fun stop(owner: IBinder, reason: String = "Stopped.") {
        active?.takeIf { it.owner == owner }?.let { finish(it, reason) }
    }

    @Synchronized
    fun stopAll() {
        active?.let { finish(it, "Helper released.") }
    }

    @Synchronized
    fun flush(owner: IBinder): Boolean {
        val session = active?.takeIf { it.owner == owner } ?: return false
        return try {
            session.flush()
            true
        } catch (e: Exception) {
            finish(session, "Uplink flush failed: ${describe(e)}")
            false
        }
    }

    @Synchronized
    fun stats(owner: IBinder): Bundle {
        return active?.takeIf { it.owner == owner }?.snapshot(true, "")
            ?: if (lastOwner == owner) Bundle(lastStats!!) else failure("No call audio session for this owner.")
    }

    @Synchronized
    private fun finish(session: Session, reason: String) {
        if (active !== session) return
        session.release()
        lastOwner = session.owner
        lastStats = session.snapshot(false, reason)
        active = null
    }

    // Retain only scalar observations; the recording configuration may disappear after stop.
    private data class RecordingDiagnostics(
        val silenced: Boolean,
        val source: Int,
        val clientRate: Int,
        val clientChannels: Int,
        val clientEncoding: Int,
        val deviceRate: Int,
        val deviceChannels: Int,
        val deviceEncoding: Int,
    )

    /** Counts only intervals corroborated by fresh, consecutive framework observations. */
    internal class CaptureSilenceDiagnostics {
        var maxMs = 0L
            private set
        private var firstSilencedAtMs = -1L
        private var lastSilencedAtMs = -1L

        fun observe(silenced: Boolean?, atMs: Long): Boolean {
            if (silenced != true) {
                firstSilencedAtMs = -1L
                lastSilencedAtMs = -1L
                return false
            }
            if (lastSilencedAtMs < 0L || atMs - lastSilencedAtMs !in 0L..MAX_CAPTURE_SILENCE_POLL_GAP_MS) {
                firstSilencedAtMs = atMs
            }
            lastSilencedAtMs = atMs
            val spanMs = atMs - firstSilencedAtMs
            maxMs = maxOf(maxMs, spanMs)
            return spanMs >= MAX_CAPTURE_SILENCED_MS
        }
    }

    /** Scalar uplink observations only. Session serializes access under its I/O lock. */
    internal class PlaybackDiagnostics {
        var writeZeroCount = 0L
            private set
        var playbackHeadFrames = -1L
            private set
        var playbackEpoch = 0L
            private set
        var playbackEpochDataBytes = 0L
            private set
        var playbackPrimeFrames = 0L
            private set
        var flushMaxMs = 0L
            private set
        private var zeroSinceMs = -1L
        private var completedZeroMaxMs = 0L
        private var lastWriteMs = -1L
        private var stoppedAtMs = -1L

        fun recordWrite(bytes: Int, atMs: Long) {
            if (bytes == 0) {
                writeZeroCount++
                if (zeroSinceMs < 0L) zeroSinceMs = atMs
            } else if (bytes > 0) {
                endZeroStreak(atMs)
                lastWriteMs = atMs
                playbackEpochDataBytes += bytes.toLong()
            }
        }

        fun endZeroStreak(atMs: Long) {
            completedZeroMaxMs = zeroMaxMs(atMs)
            zeroSinceMs = -1L
        }

        fun zeroMaxMs(nowMs: Long): Long = maxOf(completedZeroMaxMs, currentZeroStreakMs(nowMs))

        fun pendingWriteStalled(nowMs: Long): Boolean =
            stoppedAtMs < 0L && currentZeroStreakMs(nowMs) >= MAX_UPLINK_WRITE_STALL_MS

        fun lastWriteAgeMs(nowMs: Long): Long = if (lastWriteMs < 0L) -1L else observationTime(nowMs) - lastWriteMs

        fun recordPlaybackHead(rawFrames: Int) { playbackHeadFrames = rawFrames.toLong() and 0xffff_ffffL }

        fun resetPlaybackHead() { playbackHeadFrames = -1L }

        fun recordTrackFlush() {
            playbackEpoch++
            playbackEpochDataBytes = 0L
            playbackPrimeFrames = 0L
            resetPlaybackHead()
        }

        fun recordPrime(bytes: Int) { playbackPrimeFrames += bytes.toLong() / 2L }

        fun recordFlush(durationMs: Long) { flushMaxMs = maxOf(flushMaxMs, durationMs) }

        fun stop(atMs: Long) {
            endZeroStreak(atMs)
            stoppedAtMs = atMs
        }

        private fun observationTime(nowMs: Long) = stoppedAtMs.takeIf { it >= 0L } ?: nowMs

        private fun currentZeroStreakMs(nowMs: Long): Long = if (zeroSinceMs < 0L) 0L
            else observationTime(nowMs) - zeroSinceMs
    }

    private inner class Session(
        val owner: IBinder,
        private val backend: Int,
        private val record: AudioRecord,
        private val track: AudioTrack,
        private val down: ParcelFileDescriptor,
        private val up: ParcelFileDescriptor,
    ) {
        private val io = Any()
        private val playbackDiagnostics = PlaybackDiagnostics()
        private val captureSilence = CaptureSilenceDiagnostics()
        private var closed = false
        private var downBytes = 0L
        private var upBytes = 0L
        private var downPeak = 0
        private var rxDevice = -1
        private var txDevice = -1
        private var recordingDiagnostics: RecordingDiagnostics? = null
        private var clientSilencedEver = false
        private var downSize = 0
        private var downOffset = 0
        private var upSize = 0
        private var upOffset = 0
        private var downStalledSince = 0L
        private var routeMissingSince = 0L
        private val downBuffer = ByteArray(CAPTURE_RATE / 50 * 2)
        private val upBuffer = ByteArray(PLAY_RATE / 50 * 2)
        private val checkUp = StructPollfd().apply { fd = up.fileDescriptor }
        private val checkDown = StructPollfd().apply { fd = down.fileDescriptor }
        private val descriptors = arrayOf(checkUp, checkDown)
        val death = IBinder.DeathRecipient { finish(this, "Call audio owner died.") }

        fun requireCallMicrophoneMute() {
            // Telecom owns this state. AudioManager writes race its authoritative state,
            // and could re-mute after the user has explicitly taken control.
            check(manager.isMicrophoneMute) { "Telecom has not muted the handset microphone." }
        }

        fun establishRoute() {
            // AudioFlinger may not activate the output until the start threshold has data.
            // Only silence is allowed to leave the track until the route has been inspected.
            primePlayback()
            val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
            do {
                check(owner.isBinderAlive && inPstnCall()) { "Cellular call or its owner ended." }
                updateRoutes()
                if (txDevice == AudioDeviceInfo.TYPE_TELEPHONY || backend == REDIRECT) return
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Uplink telephony route was not confirmed.")
        }

        private fun primePlayback() {
            val initialFrames = if (Build.VERSION.SDK_INT >= 31) {
                track.setStartThresholdInFrames(minOf(PLAY_RATE / 50, track.bufferSizeInFrames).coerceAtLeast(1))
            } else track.bufferSizeInFrames
            val silence = ByteArray(initialFrames.coerceIn(1, PLAY_RATE / 4) * 2)
            val accepted = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            check(accepted > 0) {
                "Uplink track did not accept its initial silence."
            }
            playbackDiagnostics.recordPrime(accepted)
        }

        private fun updateRoutes() {
            rxDevice = record.routedDevice?.type ?: -1
            txDevice = track.routedDevice?.type ?: -1
            val observation = try {
                record.activeRecordingConfiguration?.let { config ->
                    val client = config.clientFormat
                    val device = config.format
                    RecordingDiagnostics(
                        config.isClientSilenced,
                        config.clientAudioSource,
                        client.sampleRate,
                        client.channelCount,
                        client.encoding,
                        device.sampleRate,
                        device.channelCount,
                        device.encoding,
                    )
                }
            } catch (_: Exception) {
                null
            }
            // Retain the last known diagnostic, but never let it extend the live guard.
            if (observation != null) {
                recordingDiagnostics = observation
                clientSilencedEver = clientSilencedEver || observation.silenced
            }
            val captureSilenced = captureSilence.observe(observation?.silenced, SystemClock.elapsedRealtime())
            check(rxDevice < 0 || rxDevice == AudioDeviceInfo.TYPE_TELEPHONY) {
                "Downlink unexpectedly routed to device type $rxDevice."
            }
            check(txDevice < 0 || txDevice == AudioDeviceInfo.TYPE_TELEPHONY) {
                "Uplink unexpectedly routed to device type $txDevice."
            }
            check(!captureSilenced) { "Android silenced the cellular audio capture." }
        }

        fun launch() {
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                var reason = "Cellular audio ended."
                var checkedAt = 0L
                try {
                    while (true) {
                        val now = SystemClock.elapsedRealtime()
                        val result = synchronized(io) {
                            if (closed) return@Thread
                            if (now - checkedAt >= DEVICE_CHECK_MS) {
                                check(inPstnCall()) { "Cellular audio mode ended." }
                                check(manager.isMicrophoneMute) { "Handset microphone was unmuted." }
                                updateRoutes()
                                checkedAt = now
                            }
                            pump(now)
                        }
                        if (!result) SystemClock.sleep(5)
                    }
                } catch (e: Exception) {
                    reason = describe(e)
                } finally {
                    finish(this, reason)
                }
            }, "mika-cellular-pcm").apply { isDaemon = true }.start()
        }

        private fun pump(now: Long): Boolean {
            // Report hangup on either end even while the model sends no audio.
            Os.poll(descriptors, 0)
            check(descriptors.none { (it.revents.toInt() and PIPE_CLOSED) != 0 }) { "Call PCM pipe closed." }
            var progressed = false
            if (downOffset == downSize) {
                val count = record.read(downBuffer, 0, downBuffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Downlink read failed ($count)." }
                if (count > 0) {
                    downPeak = maxOf(downPeak, pcmPeak(downBuffer, count))
                    downOffset = 0
                    downSize = count
                }
            }
            if (downOffset < downSize) {
                val count = noWouldBlock {
                    Os.write(down.fileDescriptor, downBuffer, downOffset, downSize - downOffset)
                }
                downBytes += count
                downOffset += count
                progressed = count > 0
                if (progressed) downStalledSince = 0L
                else if (downStalledSince == 0L) downStalledSince = now
                else check(now - downStalledSince < MAX_CAPTURE_STALL_MS) { "Downlink consumer stopped draining audio." }
            }

            // Preferred-device success is not evidence of routing. The direct path must
            // never send non-silence while the actual sink is unknown or a handset speaker.
            if (!playbackRouteReady()) {
                playbackDiagnostics.endZeroStreak(now)
                if (routeMissingSince == 0L) routeMissingSince = now
                check(txDevice < 0 && now - routeMissingSince < ROUTE_TIMEOUT_MS) {
                    "Uplink did not route to the telephony device (actual type $txDevice)."
                }
                return progressed
            }
            routeMissingSince = 0L
            if (upSize - upOffset < 2) {
                val tail = upSize - upOffset
                if (tail == 1) upBuffer[0] = upBuffer[upOffset]
                upOffset = 0
                upSize = tail + noWouldBlock { Os.read(up.fileDescriptor, upBuffer, tail, upBuffer.size - tail) }
            }
            val sampleBytes = (upSize - upOffset) and -2
            if (sampleBytes > 0) {
                val count = track.write(upBuffer, upOffset, sampleBytes, AudioTrack.WRITE_NON_BLOCKING)
                check(count >= 0) { "Uplink write failed ($count)." }
                val writtenAt = SystemClock.elapsedRealtime()
                playbackDiagnostics.recordWrite(count, writtenAt)
                // A non-blocking write can keep returning zero while the capture path still
                // progresses. Fail only with PCM waiting on this session's current route.
                check(!playbackDiagnostics.pendingWriteStalled(writtenAt)) { "Uplink playback stopped accepting audio." }
                upOffset += count
                upBytes += count
                progressed = progressed || count > 0
            }
            return progressed
        }

        fun flush() {
            val started = SystemClock.elapsedRealtime()
            synchronized(io) {
                check(!closed)
                playbackDiagnostics.endZeroStreak(SystemClock.elapsedRealtime())
                try {
                    // Remove bytes already waiting in the kernel as well as AudioTrack's queue.
                    // The app invalidates its corresponding model generation before invoking this.
                    upOffset = 0
                    upSize = 0
                    val scratch = ByteArray(4096)
                    var drained = 0
                    do {
                        val n = noWouldBlock { Os.read(up.fileDescriptor, scratch, 0, scratch.size) }
                        drained += n
                        check(drained <= MAX_FLUSH_BYTES) { "Uplink writer kept sending during the interruption." }
                    } while (n > 0)
                    track.pause()
                    track.flush()
                    playbackDiagnostics.recordTrackFlush()
                    txDevice = -1
                    track.play()
                    primePlayback()
                } finally {
                    playbackDiagnostics.recordFlush(SystemClock.elapsedRealtime() - started)
                }
            }
        }

        private fun playbackRouteReady(): Boolean = backend == REDIRECT || txDevice == AudioDeviceInfo.TYPE_TELEPHONY

        private fun samplePlaybackHead(): Boolean = try {
            playbackDiagnostics.recordPlaybackHead(track.playbackHeadPosition)
            true
        } catch (_: Exception) {
            // Vendor tracks may not expose a head position. Keep the last good value but mark it stale.
            false
        }

        fun snapshot(alive: Boolean, reason: String): Bundle = synchronized(io) {
            val playbackActive = alive && !closed
            val headFresh = playbackActive && samplePlaybackHead()
            val now = SystemClock.elapsedRealtime()
            Bundle().apply {
                putBoolean("active", alive)
                putBoolean("verified", false)
                putString("reason", reason)
                putInt("mode", backend)
                putLong("downBytes", downBytes)
                putLong("upBytes", upBytes)
                putLong("upWriteZeroCount", playbackDiagnostics.writeZeroCount)
                putLong("upWriteZeroMaxMs", playbackDiagnostics.zeroMaxMs(now))
                putLong("upLastWriteAgeMs", playbackDiagnostics.lastWriteAgeMs(now))
                putLong("upPlaybackHeadFrames", playbackDiagnostics.playbackHeadFrames)
                putLong("upPlaybackEpoch", playbackDiagnostics.playbackEpoch)
                putLong("upPlaybackEpochDataBytes", playbackDiagnostics.playbackEpochDataBytes)
                putLong("upPlaybackPrimeFrames", playbackDiagnostics.playbackPrimeFrames)
                putBoolean("upPlaybackHeadFresh", headFresh)
                putBoolean("upPlaybackRouteReady", playbackActive && playbackRouteReady())
                putLong("upFlushMaxMs", playbackDiagnostics.flushMaxMs)
                putInt("downPeak", downPeak)
                putInt("rxDevice", rxDevice)
                putInt("txDevice", txDevice)
                val config = recordingDiagnostics
                putBoolean("clientSilencedKnown", config != null)
                putBoolean("clientSilenced", config?.silenced == true)
                putBoolean("clientSilencedEver", clientSilencedEver)
                putLong("clientSilencedMaxMs", captureSilence.maxMs)
                putInt("clientSource", config?.source ?: -1)
                putInt("clientRate", config?.clientRate?.takeIf { it > 0 } ?: -1)
                putInt("clientChannels", config?.clientChannels?.takeIf { it > 0 } ?: -1)
                putInt("clientEncoding", config?.clientEncoding?.takeIf { it > 0 } ?: -1)
                putInt("deviceRate", config?.deviceRate?.takeIf { it > 0 } ?: -1)
                putInt("deviceChannels", config?.deviceChannels?.takeIf { it > 0 } ?: -1)
                putInt("deviceEncoding", config?.deviceEncoding?.takeIf { it > 0 } ?: -1)
                // This helper never changes the microphone. The runtime separately releases
                // and verifies its Telecom mute lease after the audio streams are closed.
                putBoolean("microphoneRestored", !alive)
            }
        }

        fun release() = synchronized(io) {
            if (closed) return@synchronized
            playbackDiagnostics.stop(SystemClock.elapsedRealtime())
            samplePlaybackHead()
            closed = true
            runCatching { owner.unlinkToDeath(death, 0) }
            runCatching { down.close() }
            runCatching { up.close() }
            runCatching { track.pause() }
            // Teardown never opens another playback epoch. Keep final diagnostics readable.
            runCatching { track.flush() }
            runCatching { track.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun telephonyDevice(direction: Int): AudioDeviceInfo? =
        manager.getDevices(direction).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

    private fun inPstnCall() = manager.mode == AudioManager.MODE_IN_CALL

    private fun granted(name: String) =
        context.checkSelfPermission("android.permission.$name") == PackageManager.PERMISSION_GRANTED

    private fun recordBuilder() = AudioRecord.Builder().apply {
        if (Build.VERSION.SDK_INT >= 31) setContext(context)
        setAudioFormat(format(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO))
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz downlink PCM format is unavailable." }
        setBufferSizeInBytes(maxOf(minimum, CAPTURE_RATE / 10 * 2))
    }

    private fun trackBuilder(attributes: AudioAttributes) = AudioTrack.Builder().apply {
        if (Build.VERSION.SDK_INT >= 34) setContext(context)
        setAudioAttributes(attributes)
        setAudioFormat(format(PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO))
        setTransferMode(AudioTrack.MODE_STREAM)
        val minimum = AudioTrack.getMinBufferSize(PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "24 kHz uplink PCM format is unavailable." }
        setBufferSizeInBytes(maxOf(minimum, PLAY_RATE / 20 * 2))
    }

    /** System APIs as declared by AOSP; unlike ordinary voice playback these tag the PSTN path. */
    private class RedirectApi {
        private val integer = Int::class.javaPrimitiveType!!
        private val pstn = AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null)
        private val usage = AudioAttributes::class.java.getField("USAGE_CALL_ASSISTANT").getInt(null)
        private val systemUsage = AudioAttributes.Builder::class.java.getMethod("setSystemUsage", integer)
        private val capturePreset = AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", integer)
        private val recordAttributes = AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
        private val recordMode = AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", integer)
        private val trackMode = AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", integer)

        fun record(builder: AudioRecord.Builder): AudioRecord {
            val attr = AudioAttributes.Builder()
            capturePreset.invoke(attr, MediaRecorder.AudioSource.VOICE_DOWNLINK)
            recordAttributes.invoke(builder, attr.build())
            recordMode.invoke(builder, pstn)
            return builder.build()
        }

        fun track(builder: (AudioAttributes) -> AudioTrack.Builder): AudioTrack {
            val attr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            systemUsage.invoke(attr, usage)
            val playback = builder(attr.build())
            trackMode.invoke(playback, pstn)
            return playback.build()
        }
    }

    companion object {
        const val AUTO = 0
        const val DIRECT = 1
        const val REDIRECT = 2
        private const val CAPTURE_RATE = 16000
        private const val PLAY_RATE = 24000
        private const val DEVICE_CHECK_MS = 100L
        private const val ROUTE_TIMEOUT_MS = 700L
        private const val MAX_CAPTURE_STALL_MS = 500L
        private const val MAX_CAPTURE_SILENCED_MS = 2_000L
        private const val MAX_CAPTURE_SILENCE_POLL_GAP_MS = DEVICE_CHECK_MS * 3
        private const val MAX_UPLINK_WRITE_STALL_MS = 1_500L
        private const val MAX_FLUSH_BYTES = 256 * 1024
        private val PIPE_CLOSED get() = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        internal fun candidates(mode: Int, direct: Boolean, redirect: Boolean): List<Int> = when (mode) {
            AUTO -> buildList { if (direct) add(DIRECT); if (redirect) add(REDIRECT) }
            DIRECT -> if (direct) listOf(DIRECT) else emptyList()
            REDIRECT -> if (redirect) listOf(REDIRECT) else emptyList()
            else -> emptyList()
        }

        private fun format(rate: Int, mask: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build()

        private fun nonBlocking(fd: FileDescriptor) {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }

        private inline fun noWouldBlock(block: () -> Int): Int = try {
            block()
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) 0 else throw e
        }

        internal fun pcmPeak(bytes: ByteArray, length: Int): Int {
            var max = 0
            for (i in 0 until length - 1 step 2) {
                val sample = (bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)
                max = maxOf(max, abs(sample))
            }
            return max
        }

        private fun failure(reason: String) = Bundle().apply {
            putBoolean("ready", false)
            putBoolean("verified", false)
            putBoolean("active", false)
            putString("reason", reason)
        }

        private fun describe(error: Throwable): String {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            return cause.message ?: cause.javaClass.simpleName
        }
    }
}
