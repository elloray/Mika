package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskPage
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.StartOutcome

/** 测试用任务出入口;和生产实现一样,HTTP/MCP 的归属视图不能互看任务。 */
class FakeRemoteTasks : RemoteTasks {
    val tasks = LinkedHashMap<String, RemoteTask>()
    val answers = mutableListOf<Pair<String, String>>()
    val cancelled = mutableListOf<String>()
    val steered = mutableListOf<Pair<String, String>>()
    var refuse: String? = null
    private var next = 0

    override fun scope(owner: String): RemoteTasks = object : RemoteTasks {
        override val fileAccess get() = this@FakeRemoteTasks.fileAccess?.scope(owner)
        override fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>) =
            this@FakeRemoteTasks.start(goal, conversation, requestId, attachments, owner)
        override fun get(id: String) = owned(owner, id)
        override fun list(limit: Int) = owned(owner).take(limit)
        override fun page(limit: Int, before: String?): RemoteTaskPage {
            val rows = owned(owner)
            val from = before?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val page = rows.drop(from).take(limit)
            return RemoteTaskPage(page, if (from + limit < rows.size) (from + limit).toString() else null)
        }
        override fun answer(id: String, text: String) = owned(owner, id) != null && this@FakeRemoteTasks.answer(id, text)
        override fun cancel(id: String) = owned(owner, id) != null && this@FakeRemoteTasks.cancel(id)
        override fun steer(id: String, text: String) = if (owned(owner, id) == null) null else this@FakeRemoteTasks.steer(id, text)
    }

    override fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>) =
        start(goal, conversation, requestId, attachments, null)

    private fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>, owner: String?): StartOutcome {
        refuse?.let { return StartOutcome.Refused(it) }
        if (owner != null && conversation != null) {
            tasks.keys.toList().forEach { owned(owner, it) }
            if (tasks.values.any { it.conversation == conversation && it.owner != owner })
                throw com.wangzan101.mika.core.remote.RemoteTaskError.General(
                    com.wangzan101.mika.core.remote.RemoteTaskError.Code.NOT_FOUND, "Conversation not found")
        }
        val n = ++next
        val t = RemoteTask(requestId ?: "t$n", conversation ?: com.wangzan101.mika.core.remote.RemoteTaskService.newConversation(), goal, 1000L * n, null, owner = owner)
        tasks[t.id] = t
        return StartOutcome.Started(t)
    }

    private fun owned(owner: String, id: String): RemoteTask? {
        val task = tasks[id] ?: return null
        if (task.owner == null) tasks[id] = task.copy(owner = owner)
        return tasks[id]?.takeIf { it.owner == owner }
    }

    private fun owned(owner: String): List<RemoteTask> {
        tasks.keys.toList().forEach { owned(owner, it) }
        return tasks.values.filter { it.owner == owner }.reversed()
    }

    override fun get(id: String) = tasks[id]
    override fun list(limit: Int) = tasks.values.reversed().take(limit)

    override fun answer(id: String, text: String): Boolean {
        answers += id to text
        return true
    }

    override fun cancel(id: String): Boolean {
        cancelled += id
        return true
    }

    override fun steer(id: String, text: String): String? {
        val task = tasks[id]?.takeIf { it.state is TaskState.Running } ?: return null
        steered += id to text
        val messageId = "s${steered.size}"
        tasks[id] = task.copy(steers = task.steers + com.wangzan101.mika.core.remote.RemoteSteer(messageId, "received"))
        return messageId
    }

    fun set(id: String, state: TaskState?) {
        tasks[id] = tasks.getValue(id).copy(state = state)
    }
}
