package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun indexesMessagesAndPreservesSourcesWithoutMergingSameNames() {
        val store = ConversationStore(tmp.newFolder())
        val path = "/workspace/inbox/report.pdf"
        val message = AttachmentLines.compose("看一下", listOf(path to "2 KB", path to "2 KB"))
        val output = SharedFile("report.pdf", 12, "Download/Mika/report.pdf", "content://files/1", "hash")
        store.append("a", Turn(message, "完成", "succeeded", runId = "run", atMs = 12,
            steers = listOf(AttachmentLines.compose("还有", listOf(path to "2 KB")))), listOf(output))
        store.append("a", Turn(AttachmentLines.compose("再看", listOf(path to "3 KB")), "完成", "succeeded", atMs = 30))
        store.append("b", Turn(message, "完成", "succeeded", atMs = 20),
            listOf(output.copy(name = "renamed.pdf"), output.copy(uri = "content://files/2")))

        val all = FileLibrary(store).list()
        val inputs = all.filterIsInstance<LibraryFile.Input>()
        assertEquals(2, inputs.size)
        assertEquals(listOf("a", "b"), inputs.map { it.sources.first().source.conversationId })
        assertEquals(listOf(null, 0, null), inputs.first().sources.map { it.source.steerIndex })
        assertEquals(listOf(0, 0, 1), inputs.first().sources.map { it.source.turnIndex })
        assertEquals(listOf("2 KB", "2 KB", "3 KB"), inputs.first().sources.map { it.sizeLabel })
        assertEquals("report.pdf", inputs.first().name)
        assertEquals("2 KB", inputs.first().sources.first().sizeLabel)
        assertEquals("run", inputs.first().sources.first().source.runId)
        assertEquals(30L, inputs.first().latestAtMs)
        val outputs = all.filterIsInstance<LibraryFile.Output>()
        assertEquals(2, outputs.size)
        assertEquals(listOf("a", "b"), outputs.first().sources.map { it.source.conversationId })
        assertEquals(listOf("report.pdf", "renamed.pdf"), outputs.first().sources.map { it.file.name })
        assertEquals(12L, outputs.first().sources.first().source.atMs)
    }

    @Test fun readsLegacyRecordsAndSkipsUnreadableConversations() {
        val dir = tmp.newFolder()
        File(dir, "old.json").writeText("""[{"userText":"附件:/workspace/inbox/a.txt(512 B)","status":"succeeded","files":[{"name":"a.txt","savedAs":"Download/Mika/a.txt","uri":""},{"name":"a.txt"}]}]""")
        File(dir, "broken.json").writeText("{")
        File(dir, "unreadable.json").writeText("[]")
        val store = ConversationStore(dir, readConversation = { if (it.name == "unreadable.json") throw java.io.IOException() else it.readText() })
        val library = FileLibrary(store)
        val snapshot = library.snapshot()
        assertTrue(snapshot.hasReadFailures)
        val all = snapshot.files
        assertEquals(all, library.list())
        val input = all.filterIsInstance<LibraryFile.Input>().single()
        assertNull(input.sources.single().source.atMs)
        assertNull(input.sources.single().source.runId)
        assertEquals(2, all.filterIsInstance<LibraryFile.Output>().size)
        assertEquals(0L, all.filterIsInstance<LibraryFile.Output>().first().sources.single().file.size)
        assertEquals(all, FileLibrary(store).list())
        store.delete("old")
        assertEquals(emptyList<LibraryFile>(), FileLibrary(store).list())
        File(dir, "broken.json").delete()
        File(dir, "unreadable.json").delete()
        assertFalse(library.snapshot().hasReadFailures)
    }

    @Test fun distinguishesMissingDirectoryFromDirectoryListingFailure() {
        val missing = File(tmp.root, "not-created")
        val empty = FileLibrary(ConversationStore(missing)).snapshot()
        assertFalse(empty.hasReadFailures)
        assertTrue(empty.files.isEmpty())

        val notDirectory = tmp.newFile("not-a-directory")
        val failed = FileLibrary(ConversationStore(notDirectory)).snapshot()
        assertTrue(failed.hasReadFailures)
        assertTrue(failed.files.isEmpty())
    }
}
