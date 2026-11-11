package com.wangzan101.mika.calls

import android.content.Context
import android.util.Base64
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CallTranscriptLine(val role: String, val text: String) {
    override fun toString(): String = "CallTranscriptLine(role=$role)"
}

@Serializable
data class CallRecord(
    val id: String,
    val number: String?,
    val direction: CallDirection,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val result: String,
    val transcript: List<CallTranscriptLine>,
    val handled: Boolean = false,
    val task: CallTaskBrief? = null,
    val audioReport: CallAudioReport? = null,
) {
    override fun toString(): String = "CallRecord(direction=$direction, result=$result)"
}

/** Only bounded counters; copied reports never include numbers, IDs, speech or credentials. */
@Serializable
data class CallAudioReport(
    val speechStarts: Long,
    val completeTranscripts: Long,
    val lastObservedTurn: Turn,
    val receivedAudioBytes: Long,
    val pipeWrittenBytes: Long,
    val deviceAcceptedBytes: Long?,
    val inputFramesAfterLastReply: Long? = null,
    val inputSignalFramesAfterLastReply: Long? = null,
    val captureSilencedMaxMs: Long? = null,
    val recognitionFailures: Long? = null,
    val emptyRecognitions: Long? = null,
    val providerInputCommits: Long? = null,
) {
    @Serializable
    enum class Turn { NONE, SPEECH_DETECTED, TRANSCRIPT_RECEIVED, REPLY_AUDIO_RECEIVED }

    fun supportSummary(): String = "Mika audio: speech_starts=$speechStarts " +
        "complete_transcripts=$completeTranscripts last_observed_turn=${lastObservedTurn.name} " +
        "reply_bytes=$receivedAudioBytes pipe_bytes=$pipeWrittenBytes device_bytes=${deviceAcceptedBytes ?: "unknown"} " +
        "input_frames_after_audio_done=${inputFramesAfterLastReply ?: "unknown"} " +
        "input_frames_rms_gt_200_after_audio_done=${inputSignalFramesAfterLastReply ?: "unknown"} " +
        "android_silenced_max_ms=${captureSilencedMaxMs ?: "unknown"} " +
        "asr_failures=${recognitionFailures ?: "unknown"} asr_empty=${emptyRecognitions ?: "unknown"} " +
        "provider_input_commits=${providerInputCommits ?: "unknown"}"
}

internal class CallHistory(private val storage: Storage) {
    constructor(context: Context) : this(EncryptedStorage(context))

    internal interface Storage {
        fun read(): List<CallRecord>
        fun write(records: List<CallRecord>): Boolean
    }

    enum class Result { SAVED, NOT_FOUND, WRITE_FAILED }

    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<CallRecord>>(emptyList())
    val state = mutable.asStateFlow()
    private val failed = MutableStateFlow(false)
    val writeFailed = failed.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock { readOnce() }
    }

    suspend fun append(record: CallRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            val canWrite = readOnce()
            val next = (listOf(record) + mutable.value.filterNot { it.id == record.id }).take(MAX_CALLS)
            // Keep this call visible in the current process if local storage becomes unavailable.
            mutable.value = next
            if (canWrite) persist(next) else Result.WRITE_FAILED
        }
    }

    suspend fun setHandled(id: String, handled: Boolean): Result =
        edit(id) { records -> records.map { if (it.id == id) it.copy(handled = handled) else it } }

    suspend fun delete(id: String): Result = edit(id) { records -> records.filterNot { it.id == id } }

    // Explicit clearing can also remove an unreadable encrypted history.
    suspend fun clear(): Result = withContext(Dispatchers.IO) {
        lock.withLock { persist(emptyList()) }
    }

    private suspend fun edit(id: String, change: (List<CallRecord>) -> List<CallRecord>): Result =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!readOnce()) return@withLock Result.WRITE_FAILED
                if (mutable.value.none { it.id == id }) return@withLock Result.NOT_FOUND
                persist(change(mutable.value))
            }
        }

    private fun persist(records: List<CallRecord>): Result {
        val saved = runCatching { storage.write(records) }.getOrDefault(false)
        failed.value = !saved
        if (!saved) return Result.WRITE_FAILED
        mutable.value = records
        loaded = true
        return Result.SAVED
    }

    private fun readOnce(): Boolean {
        if (loaded) return true
        val records = runCatching { storage.read() }.getOrElse {
            failed.value = true
            return false
        }
        // If a previous load failed, preserve in-memory calls without overwriting the unreadable file.
        mutable.value = (mutable.value + records).distinctBy { it.id }.take(MAX_CALLS)
        loaded = true
        return true
    }

    private class EncryptedStorage(context: Context) : Storage {
        private val prefs = context.getSharedPreferences("mika_call_history", Context.MODE_PRIVATE)
        private val cipher = KeystoreKeyCipher("mika-call-history")

        override fun read(): List<CallRecord> {
            val stored = prefs.getString(KEY, null) ?: return emptyList()
            check(stored.length <= MAX_ENCODED_CHARS)
            val bytes = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
            return try {
                Json.decodeFromString<List<CallRecord>>(bytes.toString(Charsets.UTF_8))
            } finally {
                bytes.fill(0)
            }
        }

        override fun write(records: List<CallRecord>): Boolean {
            if (records.isEmpty()) return prefs.edit().remove(KEY).commit()
            val bytes = Json.encodeToString(records).toByteArray()
            return try {
                val encrypted = Base64.encodeToString(cipher.encrypt(bytes), Base64.NO_WRAP)
                encrypted.length <= MAX_ENCODED_CHARS && prefs.edit().putString(KEY, encrypted).commit()
            } finally {
                bytes.fill(0)
            }
        }
    }

    companion object {
        const val MAX_CALLS = 50
        const val MAX_LINES = 32
        const val MAX_LINE_LENGTH = 240
        // Allow worst-case JSON escaping + Base64 for 50 bounded transcripts and task briefs.
        private const val MAX_ENCODED_CHARS = 5_000_000
        private const val KEY = "encrypted_calls_v1"
    }
}
