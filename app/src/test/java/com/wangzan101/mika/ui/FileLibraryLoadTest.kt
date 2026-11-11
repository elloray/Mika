package com.wangzan101.mika.ui

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Turn
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLibraryLoadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unreadableConversationDoesNotHideOtherFilesAndRetryRecovers() {
        val dir = tmp.newFolder()
        var failing = false
        val store = ConversationStore(dir, readConversation = {
            if (failing && it.name == "broken.json") throw IOException("unreadable")
            it.readText()
        })
        store.append("good", Turn(AttachmentLines.compose("look", listOf("/workspace/a.txt" to "1 B")), "", "succeeded"))
        store.append("broken", Turn("message", "", "succeeded"))
        failing = true
        fun load() = loadLibraryData(
            snapshot = { FileLibrary(store).snapshot() },
            titles = { store.list().associate { it.id to it.title } },
            draftTitles = mapOf("draft" to "Draft"),
            fallbackTitle = store::title,
        )

        val partial = load()
        assertTrue(partial.partial)
        assertEquals("a.txt", (partial.snapshot!!.files.single() as LibraryFile.Input).name)
        assertEquals("good", partial.titles["good"])
        assertEquals("Draft", partial.titles["draft"])

        failing = false
        val recovered = load()
        assertFalse(recovered.partial)
        assertEquals("a.txt", (recovered.snapshot!!.files.single() as LibraryFile.Input).name)
        assertTrue(recovered.titles.containsKey("broken"))
    }

    @Test fun indexFailureIsReportedAndCanBeRetried() {
        val store = ConversationStore(tmp.newFolder())
        var failing = true
        fun load() = loadLibraryData(
            snapshot = { if (failing) throw IOException("index") else FileLibrary(store).snapshot() },
            titles = { emptyMap() }, draftTitles = emptyMap(), fallbackTitle = { null },
        )
        val failed = load()
        assertNull(failed.snapshot)
        assertTrue(failed.partial)
        failing = false
        val recovered = load()
        assertNotNull(recovered.snapshot)
        assertFalse(recovered.partial)
    }

    @Test fun cancellationIsNotConvertedIntoFailure() {
        try {
            loadLibraryData(snapshot = { throw CancellationException("cancel") }, titles = { emptyMap() },
                draftTitles = emptyMap(), fallbackTitle = { null })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // The owning Compose coroutine must be cancelled normally.
        }
    }
}
