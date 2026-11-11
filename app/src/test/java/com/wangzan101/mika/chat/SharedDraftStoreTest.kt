package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class SharedDraftStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun image(store: SharedDraftStore, fileName: String, bytes: ByteArray): SharedDraftImage {
        store.imageFile(fileName)!!.writeBytes(bytes)
        return SharedDraftImage(fileName, "photo.jpg", bytes.size.toLong())
    }

    @Test fun draftAndPrivateImageSurviveStoreRecreation() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val image = image(store, "one.jpg", byteArrayOf(1, 2, 3))
        val draft = SharedDraft("conversation-1", "https://example.com", image)
        assertTrue(store.save(draft))
        assertEquals(draft, SharedDraftStore(dir).read())

        store.removeImage(draft.conversationId)
        assertEquals(draft.copy(image = null), store.read())
        assertFalse(store.imageFile("one.jpg")!!.exists())
        store.clear(draft.conversationId)
        assertNull(store.read())
    }

    @Test fun conversationsKeepTheirImagesUntilTheirOwnDraftIsCleared() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val one = image(store, "one.jpg", byteArrayOf(1))
        val two = image(store, "two.jpg", byteArrayOf(2))
        assertTrue(store.save(SharedDraft("one", "first", one)))
        assertTrue(store.save(SharedDraft("two", "second", two)))
        assertTrue(store.imageFile("one.jpg")!!.exists())
        assertTrue(store.imageFile("two.jpg")!!.exists())

        store.imageFile("orphan.tmp")!!.writeText("partial")
        store.cleanup()
        assertEquals(setOf("draft.json", "one.jpg", "two.jpg"), dir.listFiles()?.map { it.name }?.toSet())
        assertEquals(listOf("one", "two"), SharedDraftStore(dir).readAll().map { it.conversationId })
        store.clear("one")
        assertFalse(store.imageFile("one.jpg")!!.exists())
        assertTrue(store.imageFile("two.jpg")!!.exists())
        store.clear("two")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun editingAnOlderShareKeepsTheLatestShareAndOnlyReplacesItsOwnImage() {
        val store = SharedDraftStore(tmp.newFolder())
        val one = SharedDraft("one", "first", image(store, "one.jpg", byteArrayOf(1)))
        val two = SharedDraft("two", "second", image(store, "two.jpg", byteArrayOf(2)))
        assertTrue(store.save(one))
        assertTrue(store.save(two))
        val edited = one.copy(text = "edited", image = image(store, "replacement.jpg", byteArrayOf(3)))
        assertTrue(store.save(edited))
        assertEquals(listOf(edited, two), store.readAll())
        assertEquals(two, store.read())
        assertFalse(store.imageFile("one.jpg")!!.exists())
        assertTrue(store.imageFile("two.jpg")!!.exists())
        assertTrue(store.imageFile("replacement.jpg")!!.exists())
    }

    @Test fun legacySingleDraftFileIsPreservedWhenAnotherConversationIsAdded() {
        val dir = tmp.newFolder()
        File(dir, "draft.json").writeText("""{"conversation":"old","text":"keep me"}""")
        val store = SharedDraftStore(dir)
        assertEquals(SharedDraft("old", "keep me"), store.read())
        assertTrue(store.save(SharedDraft("new", "second")))
        assertEquals(listOf("keep me", "second"), SharedDraftStore(dir).readAll().map { it.text })
    }

    @Test fun blankOversizeAndForgedPathsAreRejectedWithoutChangingCurrentDraft() {
        val store = SharedDraftStore(tmp.newFolder())
        val good = image(store, "good.jpg", byteArrayOf(4))
        val draft = SharedDraft("good", "keep", good)
        assertTrue(store.save(draft))
        assertFalse(store.save(SharedDraft("blank", " ")))
        assertFalse(store.save(SharedDraft("bad", "x", SharedDraftImage("../outside", "x", 1))))
        assertFalse(store.save(SharedDraft("bad", "x", SharedDraftImage("missing.jpg", "x", 1))))
        assertFalse(store.save(SharedDraft("bad", "x".repeat(SharedDraftStore.MAX_TEXT_CHARS + 1))))
        assertEquals(draft, store.read())
    }

    @Test fun pendingDraftAndImportingImageAreProtectedAcrossCleanupAndOverwrite() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val first = image(store, "first.jpg", byteArrayOf(1))
        val pendingImg = image(store, "pending.jpg", byteArrayOf(2))

        assertTrue(store.save(SharedDraft("c1", "first", first)))
        assertTrue(store.savePending(SharedDraft("c1", "pending", pendingImg)))
        store.cleanup()
        assertTrue(store.imageFile("first.jpg")!!.exists())
        assertTrue(store.imageFile("pending.jpg")!!.exists())
        assertEquals(SharedDraft("c1", "pending", pendingImg), SharedDraftStore(dir).readPending())

        // 正在导入中的图片在同会话覆盖保存时不会被提前删除,导入结束后才释放删除。
        val nextImg = image(store, "next.jpg", byteArrayOf(3))
        store.setImporting("first.jpg", true)
        assertTrue(store.save(SharedDraft("c1", "next", nextImg)))
        assertNull(store.readPending())
        assertFalse(store.imageFile("pending.jpg")!!.exists())
        assertTrue(store.imageFile("first.jpg")!!.exists())
        assertTrue(store.imageFile("next.jpg")!!.exists())

        store.setImporting("first.jpg", false)
        assertFalse(store.imageFile("first.jpg")!!.exists())
        assertTrue(store.imageFile("next.jpg")!!.exists())
    }

    @Test fun boundedCopyRejectsSizeAndLowStorageAndRemovesTemporaryFile() = runTest {
        val dir = tmp.newFolder()
        val tmpFile = File(dir, "image.tmp")
        val final = File(dir, "image.jpg")
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).let { input ->
                val size = SharedDraftCopy.copy(input, tmpFile, final, maxBytes = 3, minFree = 0) { 100 }
                assertEquals(3L, size)
                final.readBytes()
            },
        )
        assertFalse(tmpFile.exists())

        for (lowStorage in listOf(false, true)) {
            val result = runCatching {
                SharedDraftCopy.copy(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)), tmpFile, final,
                    maxBytes = if (lowStorage) 3 else 2,
                    minFree = if (lowStorage) 10 else 0,
                ) { if (lowStorage) 0 else 100 }
            }
            assertTrue(result.isFailure)
            assertFalse(tmpFile.exists())
        }

        val cancelled = runCatching {
            SharedDraftCopy.copy(
                object : java.io.InputStream() {
                    override fun read(): Int = throw CancellationException("cancelled")
                }, tmpFile, final, maxBytes = 3, minFree = 0,
            ) { 100 }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertFalse(tmpFile.exists())
    }
}
