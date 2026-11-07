package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunPhase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpDrainTest {
    private data class Run(val conversationId: String, val goal: String) {
        val key = "$conversationId/$goal"
    }

    private var limit = ParallelLimit(1, null)
    private val queue = FollowUpQueue()
    private val registry = TaskCenter({ limit }, { it.conversationId }, { it.key }, followUps = queue) { c, g -> Run(c, g) }

    private fun item(id: String, conversation: String = "a", text: String = id) =
        QueuedFollowUp(id, conversation, text, 0L)

    @Test fun queuedFollowUpWaitsForItsOwnConversation() {
        val a = (registry.tryStart("a", "现在的") as StartResult.Started).run
        registry.tryStart("b", "别的对话")
        assertFalse(registry.deferOrStart("a", "下一件", "id-a"))
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady("a"))
        registry.onEnded(a)
        assertEquals(listOf("下一件"), registry.takeReady("a").map { it.text })
    }

    @Test fun takeReadyTakesOnePerConversation() {
        val a = (registry.tryStart("a", "现在的") as StartResult.Started).run
        registry.deferOrStart("a", "一", "id-1")
        registry.deferOrStart("a", "二", "id-2")
        registry.onEnded(a)
        val first = registry.takeReady("a").single()
        assertEquals("一", first.text)
        // 服务马上把第一件登记成新任务:第二件继续排着
        val next = (registry.tryStart("a", first.text) as StartResult.Started).run
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady("a"))
        registry.onEnded(next)
        assertEquals(listOf("二"), registry.takeReady("a").map { it.text })
    }

    @Test fun tryStartPutsTheFollowUpBackWhenTheConversationIsBusy() {
        val a = (registry.tryStart("a", "现在的") as StartResult.Started).run
        val item = item("id-a", "a", "下一件")
        val result = registry.tryStart("a", "下一件", followUp = item)
        assertSame(StartResult.AlreadyRunningInConversation, result)
        assertTrue(registry.followUps.contains("a"))
        // 这个对话还在跑,取不出来
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady("a"))
        registry.onEnded(a)
        assertEquals(listOf("id-a"), registry.takeReady("a").map { it.id })
    }

    @Test fun startingAFollowUpRemovesItOnlyWhenExecutionStarts() {
        val item = item("id-b", "b", "下一件")
        registry.followUps.add(item)
        val started = registry.tryStart("b", "下一件", followUp = item) as StartResult.Started
        assertTrue(registry.followUps.contains("b"))
        assertEquals("id-b", registry.store.all().single().followUpId)
        registry.markRunning(started.run)
        assertFalse(registry.followUps.contains("b"))
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady("b"))
    }

    @Test fun recoveryDoesNotRunAnAlreadyRegisteredFollowUpTwice() {
        val item = item("id-r", "r", "不能重复")
        registry.followUps.add(item)
        registry.tryStart("r", "不能重复", followUp = item)

        // 进程在登记后、真正执行前消失：账本和持久队列都还在，恢复只清理排队项，不再次认领。
        val recovered = TaskCenter({ limit }, { it.conversationId }, { it.key }, store = registry.store,
            followUps = registry.followUps) { c, g -> Run(c, g) }
        assertEquals(emptyList<QueuedFollowUp>(), recovered.takeReady("r"))
        assertFalse(recovered.followUps.contains("r"))
    }

    @Test fun capacityQueueKeepsDurableItemUntilRunOrCancellation() {
        limit = ParallelLimit(1, null)
        registry.tryStart("a", "占用并行位")
        val item = item("id-capacity", "b", "等并行位")
        registry.followUps.add(item)
        val queued = (registry.tryStart("b", item.text, followUp = item) as StartResult.Queued).run
        assertTrue(registry.followUps.contains("b"))
        assertEquals(RunPhase.Queued, registry.store.all().single { it.followUpId == item.id }.phase)
        assertTrue(registry.cancelIfQueued(queued))
        assertFalse(registry.followUps.contains("b"))
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady("b"))
    }

    @Test fun failedDeferredSaveIsNotReportedAsQueued() {
        val failing = FollowUpQueue(object : FollowUpQueueStore {
            override fun load(): List<QueuedFollowUp> = emptyList()
            override fun save(items: List<QueuedFollowUp>) = error("disk full")
        })
        val center = TaskCenter({ limit }, { it.conversationId }, { it.key }, followUps = failing) { c, g -> Run(c, g) }
        center.tryStart("a", "当前")
        // true 让服务走 persistFollowUp/startFollowUp 的受保护重试,而不是宣称已经排上。
        assertTrue(center.deferOrStart("a", "下一件", "id-failed"))
        assertFalse(center.followUps.contains("a"))
        assertEquals(emptyList<QueuedFollowUp>(), center.takeReady("a"))
    }

    @Test fun capacityCancelDoesNotDropTheNextFollowUp() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        registry.deferOrStart("b", "下一件", "id-b")
        assertTrue(registry.cancelIfQueued(b))
        // 容量队列里的任务被取消后,轮到它对话的下一件应该能被取出
        assertEquals(listOf("id-b"), registry.takeReady("b").map { it.id })
        registry.onEnded(a)
    }
}
