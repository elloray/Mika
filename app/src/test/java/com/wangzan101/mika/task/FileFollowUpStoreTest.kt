package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileFollowUpStoreTest {
    private fun item(id: String, conversation: String = "c", text: String = id,
        source: QueuedSource = QueuedSource.Explicit) = QueuedFollowUp(id, conversation, text, 1L, source)

    @Test fun saveAndReloadKeepsOrderTextAndSource() {
        val dir = Files.createTempDirectory("mika-followups").toFile()
        try {
            val file = dir.resolve("followups.json")
            val queue = FollowUpQueue(FileFollowUpQueueStore(file))
            queue.add(item("a1", "a", "第一件"))
            queue.add(item("b1", "b", "第二件", QueuedSource.Fallback))
            queue.add(item("a2", "a", "第三件"))
            val reopened = FollowUpQueue(FileFollowUpQueueStore(file))
            assertEquals(listOf("a1", "b1", "a2"), reopened.changes.value.map { it.id })
            assertEquals(listOf("第一件", "第二件", "第三件"), reopened.changes.value.map { it.text })
            assertEquals(QueuedSource.Fallback, reopened.changes.value[1].source)
            assertEquals(1L, reopened.changes.value[0].createdAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun claimedIsNotPersistedSoACrashRequeuesIt() {
        val dir = Files.createTempDirectory("mika-followups").toFile()
        try {
            val file = dir.resolve("followups.json")
            val queue = FollowUpQueue(FileFollowUpQueueStore(file))
            queue.add(item("a1", text = "别丢"))
            assertEquals(1, queue.claimNext({ true }).size)
            assertEquals(1, queue.changes.value.count { it.claimed })
            // 进程在这一刻被杀:重新打开,这一条仍是待处理,不会静默消失
            val reopened = FollowUpQueue(FileFollowUpQueueStore(file))
            assertEquals(listOf("a1"), reopened.changes.value.map { it.id })
            assertFalse(reopened.changes.value.single().claimed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun corruptFileIsPreservedAndQueueStartsEmpty() {
        val dir = Files.createTempDirectory("mika-followups").toFile()
        try {
            val file = dir.resolve("followups.json")
            file.writeText("not json")
            val queue = FollowUpQueue(FileFollowUpQueueStore(file))
            assertTrue(queue.changes.value.isEmpty())
            // 原文件挪到 .corrupt-* 留着,不拿空队列覆盖
            assertFalse(file.exists())
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("followups.json.corrupt-") })
            // 之后照常能写
            queue.add(item("a1"))
            assertEquals(listOf("a1"), FollowUpQueue(FileFollowUpQueueStore(file)).changes.value.map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun dropConversationPersists() {
        val dir = Files.createTempDirectory("mika-followups").toFile()
        try {
            val file = dir.resolve("followups.json")
            val queue = FollowUpQueue(FileFollowUpQueueStore(file))
            queue.add(item("a1", "a"))
            queue.add(item("b1", "b"))
            queue.dropConversation("a")
            assertEquals(listOf("b1"), FollowUpQueue(FileFollowUpQueueStore(file)).changes.value.map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }
}
