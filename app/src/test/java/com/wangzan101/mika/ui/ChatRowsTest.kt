package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRowsTest {
    private fun count(
        exchanges: List<Exchange>,
        phoneSetupNeeded: Boolean = false,
        hasBlocked: Boolean = false,
    ) = chatItemCount(exchanges, kept = null, phoneSetupNeeded, hasBlocked)

    @Test
    fun emptyAndBlockedStatesHaveTheirActualRows() {
        assertEquals(1, count(emptyList()))
        assertEquals(1, count(emptyList(), hasBlocked = true))
        assertEquals(1, count(emptyList(), phoneSetupNeeded = true, hasBlocked = true))
    }

    @Test
    fun appendedTurnAdvancesTheTailBeforeLazyListMeasuresIt() {
        val previous = List(30) { Exchange("问题 $it", emptyList(), ExchangeStatus.Done("回答 $it", null), live = false) }
        val oldTail = count(previous) - 1
        val sent = previous + Exchange("刚发送的新一轮", emptyList(), ExchangeStatus.Working, live = true)
        // 新一轮是用户气泡、工作区、状态行三项。这个目标只依赖当前状态,与旧布局里有几项无关。
        assertEquals(oldTail + 3, count(sent) - 1)
    }

    @Test
    fun streamingAnswerAndOptionalRowsStayAlignedWithTheDsl() {
        val working = Exchange("问", emptyList(), ExchangeStatus.Working, live = true, draft = "正在回答")
        assertEquals(3, count(listOf(working)))
        val done = working.copy(status = ExchangeStatus.Done("回答", null), draft = null, runId = "run")
        assertEquals(3, count(listOf(done)))
        assertEquals(4, count(listOf(done), phoneSetupNeeded = true))
        assertEquals(5, count(listOf(done), phoneSetupNeeded = true, hasBlocked = true))
    }
}
