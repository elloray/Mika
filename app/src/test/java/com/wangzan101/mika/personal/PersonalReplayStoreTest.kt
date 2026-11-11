package com.wangzan101.mika.personal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalReplayStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun removesTheOldestFinishedRecordingAndKeepsActiveFrames() {
        val root = temp.newFolder()
        var clock = 0L
        val store = PersonalReplayStore(root, limitBytes = 10, clock = { ++clock })
        store.begin("old")
        assertTrue(store.append("old", ByteArray(4)))
        store.end("old")
        store.begin("active")
        assertTrue(store.append("active", ByteArray(4)))
        store.begin("new")
        assertTrue(store.append("new", ByteArray(4)))
        assertFalse(store.append("new", ByteArray(3)))
        assertTrue(store.frames("old").isEmpty())
        assertEquals(4L, store.frames("active").single().file.length())
        assertEquals(4L, PersonalReplayStore(root, limitBytes = 10).frames("new").single().file.length())
        assertEquals(8L, root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
}
