package com.wangzan101.mika.terminal

import android.content.Context
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用户终端:进程里一份,按对话管理会话。终端不属于任何任务,不经过模型,也不写运行记录;
 * 同一个对话只开一个,全局最多 [MAX_SESSIONS] 个。对话删除、树锁收回、用户关闭都会结束会话。
 */
class TerminalHub(context: Context, private val conversations: ConversationStore) {
    private val app = context.applicationContext
    private val state = SandboxState.get(app)
    private val sandbox = AndroidSandbox(app, state)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())
    val sessions: StateFlow<Map<String, TerminalSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            conversations.deletions.collect { id -> close(id) }
        }
    }

    fun session(conversationId: String): TerminalSession? = _sessions.value[conversationId]

    /** 打开或附着这个对话的终端;运行环境没准备好时先等它(最多两分钟)。 */
    suspend fun open(conversationId: String, title: String?, cols: Int = 80, rows: Int = 24): TerminalSession {
        _sessions.value[conversationId]?.let {
            if (it.phase.value == TerminalSession.Phase.Running) return it
            close(conversationId)
        }
        return mutex.withLock {
            _sessions.value[conversationId]?.let {
                if (it.phase.value == TerminalSession.Phase.Running) return@withLock it
                close(conversationId)
            }
            // 退出、被收回、被关掉的会话不再占名额。
            _sessions.update { map -> map.filterValues { it.phase.value == TerminalSession.Phase.Running } }
            if (_sessions.value.size >= MAX_SESSIONS) {
                throw SandboxError("terminal_limit", "Close another terminal first.", recoverable = true)
            }
            val ready = sandbox.awaitReady(PREPARE_WAIT_MS)
            when (ready) {
                is SandboxStatus.Ready -> Unit
                is SandboxStatus.Unsupported -> throw SandboxError("sandbox_unsupported", ready.reason, recoverable = false)
                else -> throw SandboxError("sandbox_unavailable", "The Linux environment is not ready yet.", recoverable = true)
            }
            val readyNetwork = (ready as SandboxStatus.Ready).network
            if (!readyNetwork && state.isolationIssue != null) {
                throw SandboxError(
                    "sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so the terminal is refused while network access is off.",
                    recoverable = true,
                )
            }
            val shell = sandbox.openShell(conversationId, cols, rows)
            val session = TerminalSession(conversationId, title, readyNetwork, shell, scope)
            _sessions.update { it + (conversationId to session) }
            session
        }
    }

    fun close(conversationId: String) {
        var session: TerminalSession? = null
        _sessions.update { current ->
            session = current[conversationId]
            current - conversationId
        }
        session?.close()
    }

    fun closeAll() {
        _sessions.value.keys.toList().forEach(::close)
    }

    companion object {
        const val MAX_SESSIONS = 2
        private const val PREPARE_WAIT_MS = 120_000L
    }
}
