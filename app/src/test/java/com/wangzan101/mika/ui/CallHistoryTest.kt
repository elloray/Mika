package com.wangzan101.mika.calls

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallHistoryTest {
    private val first = record("first")
    private val second = record("second")

    @Test fun clearPersistsEmptyHistoryAndAllowsNewCalls() = runBlocking {
        val storage = FakeStorage(listOf(first, second))
        val history = CallHistory(storage)
        history.load()

        assertEquals(CallHistory.Result.SAVED, history.clear())
        assertEquals(emptyList<CallRecord>(), history.state.value)
        assertEquals(emptyList<CallRecord>(), storage.records)
        assertFalse(history.writeFailed.value)
        assertEquals(CallHistory.Result.SAVED, history.append(first))
        assertEquals(listOf(first), storage.records)
        assertEquals(listOf(first), CallHistory(storage).also { it.load() }.state.value)
    }

    @Test fun failedClearPreservesRecordsAndRetryClearsThem() = runBlocking {
        val storage = FakeStorage(listOf(first, second))
        val history = CallHistory(storage)
        history.load()
        storage.failWrite = true

        assertEquals(CallHistory.Result.WRITE_FAILED, history.clear())
        assertEquals(listOf(first, second), history.state.value)
        assertEquals(listOf(first, second), storage.records)
        assertTrue(history.writeFailed.value)

        storage.failWrite = false
        assertEquals(CallHistory.Result.SAVED, history.clear())
        assertTrue(history.state.value.isEmpty())
        assertTrue(storage.records.isEmpty())
        assertFalse(history.writeFailed.value)
    }

    @Test fun clearRecoversFromUnreadableHistoryIncludingUnsavedCalls() = runBlocking {
        val storage = FakeStorage(listOf(first)).apply { failRead = true }
        val history = CallHistory(storage)
        history.load()
        assertTrue(history.writeFailed.value)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(second))
        assertEquals(listOf(second), history.state.value)
        assertEquals(listOf(first), storage.records)

        assertEquals(CallHistory.Result.SAVED, history.clear())
        assertTrue(history.state.value.isEmpty())
        assertTrue(storage.records.isEmpty())
        assertFalse(history.writeFailed.value)
        storage.failRead = false
        assertTrue(CallHistory(storage).also { it.load() }.state.value.isEmpty())
        assertEquals(CallHistory.Result.SAVED, history.append(first))
        assertEquals(listOf(first), storage.records)
    }

    @Test fun failedClearAfterReadFailureDoesNotLoseInMemoryOrStoredCalls() = runBlocking {
        val storage = FakeStorage(listOf(first)).apply { failRead = true }
        val history = CallHistory(storage)
        history.append(second)
        storage.failWrite = true
        assertEquals(CallHistory.Result.WRITE_FAILED, history.clear())
        assertEquals(listOf(second), history.state.value)
        assertEquals(listOf(first), storage.records)
        assertTrue(history.writeFailed.value)

        storage.failRead = false
        history.load()
        assertEquals(listOf(second, first), history.state.value)
    }

    private class FakeStorage(var records: List<CallRecord>) : CallHistory.Storage {
        var failRead = false
        var failWrite = false
        override fun read(): List<CallRecord> {
            check(!failRead)
            return records
        }
        override fun write(records: List<CallRecord>): Boolean {
            if (failWrite) return false
            this.records = records.toList()
            return true
        }
    }

    private fun record(id: String) = CallRecord(
        id = id, number = "123", direction = CallDirection.INCOMING,
        startedAtMs = 1, endedAtMs = 2, result = "done", transcript = emptyList(),
    )
}
