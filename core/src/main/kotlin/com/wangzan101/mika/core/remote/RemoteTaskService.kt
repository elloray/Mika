package com.wangzan101.mika.core.remote

import kotlinx.coroutines.delay
import java.util.UUID

/** 远程入口共用的业务错误;HTTP 状态和 MCP isError 由各自适配器决定。 */
sealed class RemoteTaskError(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_ARGUMENT, NOT_FOUND, GONE, CONFLICT, UNAVAILABLE }
    class General(code: Code, message: String) : RemoteTaskError(code, message)
    class NoSuchTask(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task with id $id")
    class NoSuchRecord(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task or run record with id $id")
    class BadConversation : RemoteTaskError(Code.INVALID_ARGUMENT,
        "conversation must be one returned by an earlier remote task (looks like mcp-<uuid>)")
    class NeedsPhone : RemoteTaskError(Code.CONFLICT, RemoteStatus.ON_PHONE_NOTE)
    class QuestionClosed : RemoteTaskError(Code.CONFLICT, "The question is no longer open; check the task's status.")
}

data class RemoteWait(val task: RemoteTask, val changed: Boolean)
data class RemoteCancellation(val taskId: String, val accepted: Boolean, val status: String? = null)
data class RemoteSteerReceipt(val taskId: String, val messageId: String)

/** CLI API 与 MCP 共用的任务用例。归属由传入的已 scope 的 [tasks] 与 [records] 共同保证。 */
class RemoteTaskService(
    private val tasks: RemoteTasks,
    private val pollMs: Long = 250,
    private val records: RunExportSource? = null,
) {
    init { require(pollMs > 0) }

    val fileAccess get() = tasks.fileAccess
    val exportEnabled get() = records != null

    fun start(goal: String, conversation: String? = null, requestId: String? = null, attachments: List<String> = emptyList()): RemoteTask {
        val clean = if (attachments.isNotEmpty() && goal.isBlank()) "" else required(goal, "goal", MAX_GOAL)
        if (attachments.isNotEmpty() && fileAccess == null) invalid("This phone does not support task attachments")
        val thread = conversation?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != null && !isConversation(thread)) throw RemoteTaskError.BadConversation()
        if (requestId != null && !isUuid(requestId)) invalid("request_id must be a canonical lowercase UUID")
        if (attachments.any { !ID.matches(it) }) invalid("Invalid attachment ID")
        return when (val r = tasks.start(clean, thread, requestId, attachments)) {
            is StartOutcome.Started -> r.task
            is StartOutcome.Refused -> throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, r.message)
        }
    }

    fun get(id: String): RemoteTask {
        val clean = id.trim()
        if (clean.isEmpty()) invalid("task_id is required and must be a string")
        return tasks.get(clean) ?: throw RemoteTaskError.NoSuchTask(clean)
    }

    fun list(limit: Int = DEFAULT_LIST): List<RemoteTask> {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        return tasks.list(limit)
    }

    fun page(limit: Int = DEFAULT_LIST, before: String? = null): RemoteTaskPage {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        if (before != null && (before.isEmpty() || before.length > 256)) invalid("Invalid pagination cursor")
        return tasks.page(limit, before)
    }

    fun export(id: String): RunRecordExport.Payload {
        val source = records ?: throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, "Run export is unavailable")
        return when (val result = source.load(id.trim())) {
            RunExportLookup.Missing -> throw RemoteTaskError.NoSuchRecord(id)
            is RunExportLookup.Gone -> throw RemoteTaskError.General(RemoteTaskError.Code.GONE,
                "Run record ${result.runId} for task ${result.taskId} is no longer available")
            is RunExportLookup.Found -> RunRecordExport.of(result.record)
        }
    }

    suspend fun wait(id: String, timeoutS: Int = DEFAULT_WAIT_S): RemoteWait {
        val first = get(id)
        if (timeoutS !in 1..MAX_WAIT_S) invalid("timeout_s must be between 1 and $MAX_WAIT_S")
        val from = RemoteStatus.of(first.state)
        var now = first
        var waited = 0L
        while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeoutS * 1000L) {
            val interval = minOf(pollMs, timeoutS * 1000L - waited)
            delay(interval)
            waited += interval
            now = tasks.get(first.id) ?: break
        }
        return RemoteWait(now, RemoteStatus.of(now.state) != from)
    }

    fun answer(id: String, text: String): String {
        val task = get(id)
        val clean = required(text, "text", MAX_ANSWER)
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_FOR_ANSWER -> Unit
            RemoteStatus.WAITING_ON_PHONE -> throw RemoteTaskError.NeedsPhone()
            else -> conflict("Task ${task.id} is not waiting for an answer (status ${RemoteStatus.of(task.state)}).")
        }
        if (!tasks.answer(task.id, clean)) throw RemoteTaskError.QuestionClosed()
        return task.id
    }

    fun steer(id: String, text: String): RemoteSteerReceipt {
        val task = get(id)
        val clean = required(text, "text", MAX_STEER)
        val status = RemoteStatus.of(task.state)
        if (status == RemoteStatus.WAITING_ON_PHONE) throw RemoteTaskError.NeedsPhone()
        if (status != RemoteStatus.RUNNING) conflict("Task ${task.id} is not running (status $status). " +
            "Answer an open question or wait for it to run; steering does not start or resume a task.")
        val messageId = tasks.steer(task.id, clean)
            ?: conflict("The task no longer accepts instructions; check its status. No new task was started.")
        return RemoteSteerReceipt(task.id, messageId)
    }

    fun cancel(id: String): RemoteCancellation {
        val task = get(id)
        val status = RemoteStatus.of(task.state)
        return if (RemoteStatus.finished(status)) RemoteCancellation(task.id, false, status)
        else RemoteCancellation(task.id, tasks.cancel(task.id))
    }

    private fun required(value: String, name: String, max: Int): String {
        val clean = value.trim()
        if (clean.isEmpty()) invalid("$name is required and must be a non-empty string")
        if (clean.length > max) invalid("$name is too long (at most $max characters)")
        return clean
    }

    private fun invalid(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, message)
    private fun conflict(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, message)

    companion object {
        const val DEFAULT_WAIT_S = 30
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        const val MAX_STEER = 4_000
        private const val CONVERSATION_PREFIX = "mcp-"
        private val ID = Regex("[A-Za-z0-9_-]+")
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()
        private fun isUuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) && isUuid(id.removePrefix(CONVERSATION_PREFIX))
    }
}
