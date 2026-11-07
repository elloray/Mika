package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpQueueTest {
    private fun item(id: String, conversation: String = "c", text: String = id, source: QueuedSource = QueuedSource.Explicit) =
        QueuedFollowUp(id, conversation, text, 0L, source)

    @Test fun fifoPerConversationAndOneAtATime() {
        val q = FollowUpQueue()
        q.add(item("a1"))
        q.add(item("a2"))
        q.add(item("b1", "b"))
        // 每个对话只取最早的一条
        assertEquals(listOf("a1", "b1"), q.claimNext({ true }).map { it.id })
        // 取走的第一条还留在 changes 里(claimed),不会再被取;第二条继续排着
        assertEquals(listOf("a2"), q.claimNext({ true }, "c").map { it.id })
        assertEquals(emptyList<QueuedFollowUp>(), q.claimNext({ true }, "c"))
        assertEquals(listOf("a1", "a2", "b1"), q.changes.value.map { it.id })
        assertEquals(listOf("a1", "a2", "b1"), q.changes.value.map { it.id })
        q.remove("a1")
        assertEquals(listOf("a2", "b1"), q.changes.value.map { it.id })
    }

    @Test fun onlyFreeConversationsAreClaimed() {
        val q = FollowUpQueue()
        q.add(item("a1", "a"))
        q.add(item("b1", "b"))
        assertEquals(listOf("b1"), q.claimNext({ it == "b" }).map { it.id })
        assertEquals(emptyList<QueuedFollowUp>(), q.claimNext({ it == "b" }))
        assertTrue(q.contains("a"))
    }

    @Test fun claimedCannotBeCancelledButUnclaimedCan() {
        val q = FollowUpQueue()
        q.add(item("a1"))
        assertEquals(listOf("a1"), q.claimNext({ true }).map { it.id })
        assertNull(q.cancel("a1"))
        assertTrue(q.remove("a1"))
        q.add(item("a2"))
        assertEquals("a2", q.cancel("a2")?.id)
        assertFalse(q.contains("c"))
    }

    @Test fun addIsIdempotentAndUnclaims() {
        val q = FollowUpQueue()
        q.add(item("a1", text = "旧"))
        q.claimNext({ true })
        q.add(item("a1", text = "新"))
        assertEquals(listOf("新"), q.changes.value.map { it.text })
        assertEquals(listOf("a1"), q.claimNext({ true }).map { it.id })
    }

    @Test fun dropConversationRemovesOnlyThatConversation() {
        val q = FollowUpQueue()
        q.add(item("a1", "a"))
        q.add(item("b1", "b"))
        q.dropConversation("a")
        assertEquals(listOf("b"), q.changes.value.map { it.conversationId })
        assertFalse(q.contains("a"))
        assertTrue(q.contains("b"))
    }

    @Test fun failedSaveIsNotReportedAsDurableQueue() {
        val failing = object : FollowUpQueueStore {
            override fun load(): List<QueuedFollowUp> = emptyList()
            override fun save(items: List<QueuedFollowUp>) = error("disk full")
        }
        val q = FollowUpQueue(failing)
        assertFalse(q.add(item("a1")))
        assertEquals(emptyList<QueuedFollowUp>(), q.changes.value)
        assertFalse(q.contains("c"))
        assertEquals(emptyList<QueuedFollowUp>(), q.claimNext({ true }))
    }

    @Test fun failedCancellationSaveKeepsTheItemVisibleAndPending() {
        val store = MemoryFollowUpQueueStore()
        val q = FollowUpQueue(store)
        q.add(item("a1"))
        val failing = object : FollowUpQueueStore {
            override fun load() = store.load()
            override fun save(items: List<QueuedFollowUp>) = error("read only")
        }
        val reopened = FollowUpQueue(failing)
        assertNull(reopened.cancel("a1"))
        assertEquals(listOf("a1"), reopened.pendingFor("c").map { it.id })
    }
}
