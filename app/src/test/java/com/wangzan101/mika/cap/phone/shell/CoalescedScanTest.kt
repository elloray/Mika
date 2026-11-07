package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescedScanTest {
    private class Scheduler {
        var now = 0L
        val jobs = mutableListOf<Pair<Long, Runnable>>()
        fun schedule(work: Runnable) { jobs += now + 50 to work }
        fun cancel(work: Runnable) { jobs.removeAll { it.second === work } }
        fun advance(to: Long) {
            while (true) {
                val next = jobs.minByOrNull { it.first }?.takeIf { it.first <= to } ?: break
                jobs.remove(next)
                now = next.first
                next.second.run()
            }
            now = to
        }
    }

    @Test fun continuousEventsDoNotStarveTheScan() {
        val scheduler = Scheduler()
        val scans = mutableListOf<Long>()
        val scan = CoalescedScan(scheduler::schedule, scheduler::cancel) { scans += scheduler.now }
        for (at in 0L..300L step 10) { scheduler.advance(at); scan.request() }
        assertEquals(50L, scans.first())
        assertTrue("scan must run while events are still arriving", scans.size >= 5)
        assertEquals(1, scheduler.jobs.size)
    }

    @Test fun eventDuringScanSchedulesAnotherPass() {
        val scheduler = Scheduler()
        var calls = 0
        lateinit var scan: CoalescedScan
        scan = CoalescedScan(scheduler::schedule, scheduler::cancel) { if (++calls == 1) scan.request() }
        scan.request()
        scheduler.advance(100)
        assertEquals(2, calls)
        assertTrue(scheduler.jobs.isEmpty())
    }

    @Test fun closeCancelsPendingAndFutureRequests() {
        val scheduler = Scheduler()
        var calls = 0
        val scan = CoalescedScan(scheduler::schedule, scheduler::cancel) { calls++ }
        scan.request()
        val pending = scheduler.jobs.single().second
        scan.close()
        scan.request()
        pending.run() // 即使已出队的回调晚到了,也不能继续扫描。
        scheduler.advance(500)
        assertEquals(0, calls)
        assertTrue(scheduler.jobs.isEmpty())
    }
}
