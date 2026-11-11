package com.wangzan101.mika.core.personal

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalAgentTest {
    private class Store(initial: PersonalState = PersonalState()) : PersonalStore {
        override val state = MutableStateFlow(initial)
        override fun update(change: (PersonalState) -> PersonalState) { state.value = change(state.value) }
    }
    private class Actions : PersonalActions {
        val started = mutableListOf<Pair<String, String>>()
        val revised = mutableListOf<Pair<String, String>>()
        override fun start(conversationId: String, goal: String) { started += conversationId to goal }
        override fun revise(conversationId: String, instruction: String): String { revised += conversationId to instruction; return "已补充" }
        override fun cancel(conversationId: String) = "已请求停止"
        override fun remember(text: String, quote: String) = "已记住"
        override fun memory() = ""
    }
    private class Model(val block: suspend (List<Message>) -> ModelReply) : ChatModel {
        override val capabilities = Capabilities(true, false, 128_000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = block(messages)
    }
    private fun text(value: String) = ModelReply(listOf(Part.Text(value)), StopReason.EndTurn, null)
    private fun call(id: String, name: String, args: String) = ModelReply(listOf(Part.ToolCall(id, name, args)), StopReason.ToolUse, null)
    private fun flatten(messages: List<Message>) = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

    @Test fun chatDoesNotCreateWorkAndLaterTurnReceivesHistory() = runTest {
        val store = Store()
        val actions = Actions()
        val histories = mutableListOf<String>()
        val agent = PersonalAgent(store, backgroundScope, { Model { histories += flatten(it); text("接着聊") } }, actions)
        agent.send("昨天我想做一个个人助手")
        runCurrent()
        agent.send("继续昨天那个想法")
        runCurrent()
        assertTrue(actions.started.isEmpty())
        assertEquals(2, histories.size)
        assertTrue(histories.last().contains("昨天我想做一个个人助手"))
        assertEquals(2, store.state.value.messages.count { it.role == "assistant" })
    }

    @Test fun replyMetadataIsKeptOutOfConversationText() = runTest {
        val prefix = "[发送时间：2026-11-05T05:30:00+08:00[Asia/Shanghai]]\n任务已受理 [task-id]：\n"
        val previous = PersonalMessage("previous", "assistant", prefix + "上次找到两篇。", 1L)
        val receipt = PersonalMessage("receipt", "task", "整理阅读清单", 2L, "task-id")
        val store = Store(PersonalState(messages = listOf(previous, receipt)))
        val agent = PersonalAgent(store, backgroundScope, { Model { history ->
            assertEquals("上次找到两篇。\n接着说", flatten(history))
            text(prefix + "现在找到第三篇。")
        } }, Actions())
        agent.send("接着说")
        runCurrent()
        assertEquals("上次找到两篇。", previous.conversationText())
        assertEquals("现在找到第三篇。", store.state.value.messages.last().text)
        assertEquals("用户原话里提到了任务已受理", previous.copy(role = "user", text = "用户原话里提到了任务已受理").conversationText())
    }

    @Test fun delegatedTaskDoesNotBlockAnotherChatAndResultIsDeliveredOnce() = runTest {
        val store = Store()
        val actions = Actions()
        var invocation = 0
        val model = Model {
            when (invocation++) {
                0 -> call("c1", "delegate_task", """{"title":"整理资料","goal":"查找开源个人助手项目"}""")
                1 -> text("任务已经受理，你可以继续聊。")
                else -> text("我们聊聊你的新想法。")
            }
        }
        val agent = PersonalAgent(store, backgroundScope, { model }, actions)
        agent.send("帮我查几个开源个人助手项目"); runCurrent()
        val task = store.state.value.tasks.single()
        assertEquals(1, actions.started.size)
        agent.send("对了，我想聊聊最近的困惑"); runCurrent()
        assertEquals(1, actions.started.size)
        assertTrue(store.state.value.messages.last().text.contains("新想法"))
        agent.updateTask(task.id, "completed", result = "找到了两个项目")
        agent.updateTask(task.id, "completed", result = "找到了两个项目")
        assertEquals(1, store.state.value.messages.count { it.role == "assistant" && it.taskId == task.id })
        assertTrue(store.state.value.tasks.single().delivered)
    }

    @Test fun explicitAmendmentTargetsOwnedTaskWithoutCreatingNewOne() = runTest {
        val task = PersonalTask("owned", "thread", "资料", "查资料", "user", 1L, status = "running")
        val store = Store(PersonalState(tasks = listOf(task)))
        val actions = Actions()
        var invocation = 0
        val agent = PersonalAgent(store, backgroundScope, { Model {
            if (invocation++ == 0) call("c1", "revise_task", """{"task_id":"owned","instruction":"只看开源项目"}""") else text("范围已更新")
        } }, actions)
        agent.send("刚才的资料只看开源项目"); runCurrent()
        assertEquals(listOf("thread" to "只看开源项目"), actions.revised)
        assertTrue(actions.started.isEmpty())
        assertEquals(1, store.state.value.tasks.size)
    }

    @Test fun duplicateDelegationInOneTurnCannotLaunchWorkTwice() = runTest {
        val store = Store()
        val actions = Actions()
        var invocation = 0
        val agent = PersonalAgent(store, backgroundScope, { Model {
            if (invocation++ < 2) call("c" + invocation, "delegate_task", """{"title":"资料","goal":"查资料"}""")
            else text("已受理")
        } }, actions)
        agent.send("帮我查资料"); runCurrent()
        assertEquals(1, actions.started.size)
        assertEquals(1, store.state.value.tasks.size)
    }

    @Test fun invalidTaskIdCannotModifyAnotherConversation() = runTest {
        val actions = Actions()
        var invocation = 0
        val agent = PersonalAgent(Store(), backgroundScope, { Model {
            if (invocation++ == 0) call("c1", "revise_task", """{"task_id":"other","instruction":"改一下"}""") else text("找不到这项任务")
        } }, actions)
        agent.send("改一下任务"); runCurrent()
        assertTrue(actions.revised.isEmpty())
    }

    @Test fun messageArrivingDuringReplyIsHandledOnNextTurn() = runTest {
        val store = Store()
        val release = CompletableDeferred<Unit>()
        var invocation = 0
        val histories = mutableListOf<String>()
        val agent = PersonalAgent(store, backgroundScope, { Model {
            histories += flatten(it)
            if (invocation++ == 0) release.await()
            text("回复" + invocation)
        } }, Actions())
        agent.send("第一句"); runCurrent()
        agent.send("第二句"); runCurrent()
        assertEquals(2, store.state.value.messages.count { it.role == "user" })
        release.complete(Unit); runCurrent()
        assertEquals(2, histories.size)
        assertTrue(histories.last().contains("第二句"))
        assertFalse(store.state.value.messages.any { !it.handled })
    }

    @Test fun restartDoesNotReplayPendingActionsAndRetainsHistory() = runTest {
        val store = Store(PersonalState(messages = listOf(PersonalMessage("pending", "user", "查资料", 1, handled = false))))
        val actions = Actions()
        PersonalAgent(store, backgroundScope, { error("must not call model on recovery") }, actions)
        runCurrent()
        assertTrue(actions.started.isEmpty())
        assertTrue(store.state.value.messages.first().handled)
        assertTrue(store.state.value.messages.last().error)
    }

    @Test fun noModelIsAnHonestErrorNotASimulatedReply() = runTest {
        val store = Store()
        val agent = PersonalAgent(store, backgroundScope, { null }, Actions())
        agent.send("你好"); runCurrent()
        assertTrue(store.state.value.messages.last().error)
        assertTrue(store.state.value.messages.last().text.contains("主模型"))
    }
    @Test fun cancelledTaskCannotLaterDeliverAFakeSuccess() = runTest {
        val task = PersonalTask("owned", "thread", "资料", "查资料", "user", 1L)
        val store = Store(PersonalState(tasks = listOf(task)))
        val agent = PersonalAgent(store, backgroundScope, { null }, Actions())
        agent.updateTask("owned", "cancelled", result = "用户停止了")
        agent.updateTask("owned", "completed", result = "不应送达")
        assertEquals("cancelled", store.state.value.tasks.single().status)
        assertEquals(listOf("用户停止了"), store.state.value.messages.map { it.text })
    }

    @Test fun longConversationCreatesCheckpointWithoutDeletingOriginals() = runTest {
        val original = (1..56).map { PersonalMessage("old-" + it, if (it % 2 == 0) "assistant" else "user", "原话" + it, it.toLong()) }
        val store = Store(PersonalState(messages = original))
        var summaries = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 128_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (tools.isEmpty()) { summaries++; return text("用户正在探索一个长期个人助手，尚未授权行动。") }
                return text("我们继续聊")
            }
        }
        val agent = PersonalAgent(store, backgroundScope, { model }, Actions())
        agent.send("继续刚才的想法"); runCurrent()
        assertEquals(1, summaries)
        assertTrue(store.state.value.covered > 0)
        assertTrue(store.state.value.summary.contains("尚未授权"))
        assertEquals(original, store.state.value.messages.take(56))
    }

    @Test fun lateStopSummaryUpdatesTheSameBubbleWithoutDuplicatingDelivery() = runTest {
        val task = PersonalTask("owned", "thread", "资料", "查资料", "user", 1L)
        val store = Store(PersonalState(tasks = listOf(task)))
        val agent = PersonalAgent(store, backgroundScope, { null }, Actions())
        agent.updateTask("owned", "cancelled", result = "已取消")
        agent.updateTask("owned", "cancelled", result = "找到两个项目，尚未整理完成。")
        assertEquals(1, store.state.value.messages.size)
        assertEquals("找到两个项目，尚未整理完成。", store.state.value.messages.single().text)
        assertEquals("cancelled", store.state.value.tasks.single().status)
    }

}
