package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/** 电脑交来的一个任务:[state] 为空表示已经交给任务服务、还没登记上(马上就会有)。 */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
    val steers: List<RemoteSteer> = emptyList(),
    val runId: String? = null,
    val files: List<TaskFile> = emptyList(),
    /** 访问令牌的不可逆摘要;只用于归属判断,从不出现在响应里。旧数据首次读取时归属给首个已授权客户端。 */
    val owner: String? = null,
)

data class RemoteTaskPage(val tasks: List<RemoteTask>, val nextBefore: String? = null)

/** 只报告电脑追加的消息;joined 表示进入当前任务上下文,不是执行成功。 */
data class RemoteSteer(val messageId: String, val state: String)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/** 访问令牌只以 SHA-256 摘要进入任务层;接口、日志和导出都不保存或暴露原令牌。 */
object RemoteOwnership {
    const val UNSCOPED = "unscoped"
    fun of(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * App 一侧给电脑连接的任务出入口。[scope] 是每次已认证请求的唯一归属入口;HTTP 与 MCP 都必须使用它,
 * 使同一个 computer-access token 才能读、改、上传、下载或导出自己创建的任务。
 */
interface RemoteTasks {
    val fileAccess: RemoteTaskFiles? get() = null
    fun scope(owner: String): RemoteTasks = this
    fun start(goal: String, conversation: String?, requestId: String? = null, attachments: List<String> = emptyList()): StartOutcome
    fun get(id: String): RemoteTask?
    /** 新的在前。保留给原有 MCP 与直接服务调用。 */
    fun list(limit: Int): List<RemoteTask>
    /** 稳定的游标页;不支持游标的旧实现仍可按原有列表工作。 */
    fun page(limit: Int, before: String? = null): RemoteTaskPage = RemoteTaskPage(list(limit))
    /** 只在任务正等着回答追问时调用;交给任务时返回 true。 */
    fun answer(id: String, text: String): Boolean
    /** 原子确认消息进入当前任务的收件箱;不能转成新任务。返回消息编号,拒绝为 null。 */
    fun steer(id: String, text: String): String?
    fun cancel(id: String): Boolean
}

/** 任务状态对电脑的说法;只读任务自己的状态,不另记。 */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val QUEUED = "queued"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered remotely. " +
        "Mika has shown its usual notification there; keep waiting or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> when {
            state.reason.needsYou -> WAITING_ON_PHONE
            state.reason.queued -> QUEUED
            else -> PAUSED
        }
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** 一个任务此刻的样子:状态、最近的进度说明、等什么、结果。 */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        t.runId?.let { put("run_id", it) }
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "Mika is asking a question. Answer this question remotely or on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        if (t.files.isNotEmpty()) putJsonArray("files") {
            t.files.forEach { file -> add(buildJsonObject {
                put("id", file.id); put("name", file.name); put("size", file.size)
                file.sha256?.let { put("sha256", it) }
            }) }
        }
        put("answerable", s is TaskState.WaitingInput)
        if (t.steers.isNotEmpty()) putJsonArray("steers") {
            t.steers.forEach { add(buildJsonObject { put("message_id", it.messageId); put("state", it.state) }) }
        }
    }
}
