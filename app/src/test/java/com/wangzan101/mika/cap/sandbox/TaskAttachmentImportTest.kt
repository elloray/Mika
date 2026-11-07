package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TaskAttachmentImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun collisionNamesReferToTheRightBytesAndRefusedTasksRollBackOnlyTheirOwnCopies() = runBlocking {
        val ws = temp.newFolder()
        val inbox = File(ws, "inbox").apply { mkdir() }
        val earlier = File(inbox, "清单.csv").apply { writeText("original") }
        val items = listOf("A", "B").map { "清单.csv" to temp.newFile().apply { writeText(it) } }
        val tree = SandboxTree()
        val result = Attachments.withTaskFiles(items, ws, JVM_GATE, tree, { true }) { records ->
            records.map { (path, size) ->
                assertEquals(1L, size)
                File(ws, path.removePrefix("/workspace/")).readText()
            }
        }
        assertEquals(listOf("A", "B"), result)
        val before = inbox.list()!!.toSet()
        assertEquals(setOf("清单.csv", "清单 (2).csv", "清单 (3).csv"), before)
        val refused = runCatching {
            Attachments.withTaskFiles(items, ws, JVM_GATE, tree, { true }) { throw IOException("refused") }
        }.exceptionOrNull()
        assertTrue(refused is IOException)
        assertEquals(before, inbox.list()!!.toSet())
        assertEquals("original", earlier.readText())
    }
}
