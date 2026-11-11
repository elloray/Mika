package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadIdsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(limit: Int = 200) = DownloadIds(File(tmp.root, "browser_download_ids.txt"), limit)

    @Test
    fun idsPersistAndCanBeRemoved() {
        store().add(7)
        store().add(8)
        assertEquals(listOf(7L, 8L), store().all().toList())
        store().remove(7)
        assertEquals(listOf(8L), store().all().toList())
    }

    @Test
    fun keepsOnlyTheLimitOldestFirst() {
        val s = store(limit = 3)
        (1L..5L).forEach { s.add(it) }
        assertEquals(listOf(3L, 4L, 5L), s.all().toList())
    }

    @Test
    fun refreshDropsIdsTheSystemNoLongerHas() {
        val s = store()
        s.add(1); s.add(2); s.add(3)
        s.pruneMissing(s.all(), setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), s.all().toList())
    }

    @Test
    fun refreshKeepsDownloadsAddedAfterTheQueryStarted() {
        val s = store()
        s.add(1)
        s.add(2)
        val queried = s.all()
        s.add(3)
        s.pruneMissing(queried, setOf(1L))
        assertEquals(listOf(1L, 3L), store().all().toList())
    }
}
