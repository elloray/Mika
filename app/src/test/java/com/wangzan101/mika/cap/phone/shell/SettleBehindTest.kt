package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.SettleBehind.Plan
import com.wangzan101.mika.cap.phone.shell.SettleBehind.Seen
import org.junit.Assert.assertEquals
import org.junit.Test

class SettleBehindTest {
    private fun behind(id: Int) = Seen(id, onMain = true, visible = false)
    private fun front(id: Int) = Seen(id, onMain = true, visible = true)

    @Test fun nothingMovedIsDoneAtOnce() {
        assertEquals(Plan.Done(emptySet(), emptySet(), emptySet()), SettleBehind.plan(emptySet(), emptyList(), 0, null))
    }

    @Test fun allBehindIsKept() {
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 0, null))
    }

    @Test fun visibleDuringTheTransitionWaitsInsteadOfFailing() {
        // 第一次看到就再排一次
        assertEquals(Plan.Wait(setOf(1), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 0, null))
        // 刚排过:只等,不连着排
        assertEquals(Plan.Wait(emptySet(), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 300, 300))
        // 隔够了再排
        assertEquals(Plan.Wait(setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), 900, SettleBehind.REORDER_EVERY_MS))
        // 落定了:留下
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 1_200, 300))
    }

    @Test fun stillVisibleAtTheDeadlineTakesTheFallback() {
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), SettleBehind.TIMEOUT_MS, 100))
        assertEquals(Plan.Done(emptySet(), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), SettleBehind.TIMEOUT_MS + 500, null))
    }

    @Test fun closeOutRechecksUnderTheLock() {
        // 超时还看得见的仍看得见:走退路;另一个留下
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(front(1), behind(2))))
        // 超时那个这会儿落定了:留下
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(behind(1), behind(2))))
        // 已落定、此刻又看得见的是用户点开的:照留,不拿走
        assertEquals(Plan.Done(setOf(2), emptySet(), emptySet()), SettleBehind.closeOut(setOf(2), emptySet(), listOf(front(2))))
        // 等的时候被别的任务拿到它屏上、或关了:不算留下,也不走退路
        assertEquals(Plan.Done(emptySet(), emptySet(), setOf(1, 2)),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(Seen(1, onMain = false, visible = true))))
    }

    @Test fun tasksNoLongerOnMainAreDroppedNotKept() {
        // 被别的任务拿到另一块屏上、或已经关了(没列出)
        val elsewhere = Seen(3, onMain = false, visible = true)
        assertEquals(Plan.Wait(setOf(1), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(front(1), elsewhere), 0, null))
        assertEquals(Plan.Done(setOf(1), emptySet(), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(behind(1), elsewhere), 0, null))
        // 超时时已不在主屏的不走退路
        assertEquals(Plan.Done(emptySet(), setOf(1), setOf(3)),
            SettleBehind.plan(setOf(1, 3), listOf(front(1), elsewhere), SettleBehind.TIMEOUT_MS, null))
    }
}
