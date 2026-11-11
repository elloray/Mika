package com.wangzan101.mika.terminal

import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 环形缓冲:保留最近 [cap] 字节,UI 重新附着时从这里重建画面。 */
class TerminalRingBuffer(private val cap: Int) {
    private val buf = ByteArray(cap)
    private var start = 0
    private var size = 0
    private var dropped = 0L

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size >= cap) {
            bytes.copyInto(buf, 0, bytes.size - cap)
            start = 0
            size = cap
            dropped += bytes.size.toLong() - cap
            return
        }
        val overflow = size + bytes.size - cap
        if (overflow > 0) {
            start = (start + overflow) % cap
            size -= overflow
            dropped += overflow
        }
        val end = (start + size) % cap
        val first = minOf(bytes.size, cap - end)
        bytes.copyInto(buf, end, 0, first)
        if (first < bytes.size) bytes.copyInto(buf, 0, first, bytes.size)
        size += bytes.size
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val first = minOf(size, cap - start)
        buf.copyInto(out, 0, start, start + first)
        if (first < size) buf.copyInto(out, first, 0, size - first)
        return out
    }

    /** 被丢掉的最旧字节数;0 表示从会话开始的内容都还在。 */
    @Synchronized
    fun droppedBytes(): Long = dropped
}

/**
 * 一个用户终端的会话:shell 进程、输出缓冲、输入和历史。
 * 输出同时进环形缓冲(给重新附着的界面)和 [output](给正在看的界面)。
 */
class TerminalSession internal constructor(
    val conversationId: String,
    val title: String?,
    /** 会话启动时冻结的联网状态;中途改设置只影响新会话。 */
    val network: Boolean,
    private val shell: SandboxShell,
    scope: CoroutineScope,
) {
    enum class Phase { Running, Exited, Revoked, Closed }

    private val _phase = MutableStateFlow(Phase.Running)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val ring = TerminalRingBuffer(1 shl 20)
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val output: kotlinx.coroutines.flow.SharedFlow<ByteArray> = _output

    /** 本次会话里用户输入过的行;只在内存里,不写盘。 */
    val history = ArrayDeque<String>()

    /** PTY 自己回显,界面不要再把输入画一遍。 */
    val echoInput = false

    private val shellClosed = AtomicBoolean()
    private val lifecycle = scope.launch {
        try {
            val revocations = launch {
                shell.revoked.collect { revoked ->
                    if (revoked) _phase.compareAndSet(Phase.Running, Phase.Revoked)
                }
            }
            val reader = launch(Dispatchers.IO) { drainOutput() }
            val code = shell.exited.await()
            _exitCode.value = code
            _phase.update {
                when {
                    it != Phase.Running -> it
                    shell.revoked.value -> Phase.Revoked
                    else -> Phase.Exited
                }
            }
            revocations.cancel()
            reader.join()
        } finally {
            releaseShell()
        }
    }

    private suspend fun drainOutput() {
        val buf = ByteArray(16 * 1024)
        try {
            shell.output.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    ring.append(chunk)
                    _output.emit(chunk)
                }
            }
        } catch (_: IOException) {
            // A closed pipe can race with exit, revocation or an explicit close.
        }
    }

    fun write(line: String) {
        if (line.isNotEmpty()) {
            history.addLast(line)
            while (history.size > 200) history.removeFirst()
        }
        writeBytes((line + "\n").toByteArray())
    }

    fun writeBytes(bytes: ByteArray) {
        shell.input.write(bytes)
        shell.input.flush()
    }

    /** 界面大小变了(转屏、字体变化、分屏);mikarun 会把新的行列数写进 pty。 */
    fun resize(cols: Int, rows: Int) {
        if (cols !in 1..1000 || rows !in 1..1000) return
        runCatching { shell.resize(cols, rows) }
    }

    /** 从会话开始到现在的输出(最旧的超出缓冲的部分已经丢掉)。 */
    fun snapshot(): ByteArray = ring.snapshot()

    fun droppedBytes(): Long = ring.droppedBytes()

    fun close() {
        _phase.value = Phase.Closed
        lifecycle.cancel()
        releaseShell()
    }

    private fun releaseShell() {
        if (shellClosed.compareAndSet(false, true)) runCatching { shell.close() }
    }
}
