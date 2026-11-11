package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverTimeoutTest {
    private val port = TakeoverControllerTest.FakePort()
    private val waiting = TaskState.WaitingTakeover("login", "请登录")

    private fun TestScope.takeover() = Takeover(port, backgroundScope) { testScheduler.currentTime }

    @Test
    fun waitingRemindsWithoutEndingTheTask() = runTest {
        var reminders = 0
        val t = Takeover(port, backgroundScope, now = { testScheduler.currentTime }, remind = { reminders++ })
        t.onState(waiting)
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1)
        testScheduler.runCurrent()
        assertTrue(port.log.isEmpty())
        assertEquals(0, reminders)
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
        assertTrue(port.log.isEmpty())
        assertTrue(port.results.isEmpty())
        assertEquals(2, reminders)
        assertEquals(Takeover.Stage.Waiting, t.stage.value)
    }

    @Test
    fun noTimeoutWhileHandedOver() = runTest {
        val t = takeover()
        t.onState(waiting)
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS / 2)
        t.handOver()
        port.log.clear()
        // 人在主屏上操作,多久都不把 App 抽走
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS * 4)
        testScheduler.runCurrent()
        assertTrue(port.log.isEmpty())
        assertEquals(Takeover.Stage.HandedOver, t.stage.value)
        assertTrue(t.handBack())
        assertEquals(HandBackOutcome.complete, port.results.single().outcome)
    }

    @Test
    fun waitingDoesNotMoveTheApp() = runTest {
        val t = takeover()
        t.onState(waiting)
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
        // 提醒不挪动、不改声音，也不结束等待。
        assertTrue(port.log.isEmpty())
        assertEquals(TakeoverControllerTest.AGENT, port.displays[1])
        assertEquals(Takeover.Stage.Waiting, t.stage.value)
    }

    @Test
    fun answeredTakeoverDoesNotTimeOutLater() = runTest {
        val t = takeover()
        t.onState(waiting)
        t.skip()
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS * 2)
        testScheduler.runCurrent()
        assertEquals(listOf("resume:skipped"), port.log)
    }

    @Test
    fun newWaitRestartsReminders() = runTest {
        var reminders = 0
        val t = Takeover(port, backgroundScope, now = { testScheduler.currentTime }, remind = { reminders++ })
        t.onState(waiting)
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1_000)
        t.handBack()
        t.onState(TaskState.Running(4, "phone_observe"))
        t.onState(TaskState.WaitingTakeover("login", "请再登录一次"))
        testScheduler.advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1)
        testScheduler.runCurrent()
        assertEquals(listOf("resume:complete"), port.log)
        assertEquals(0, reminders)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(listOf("resume:complete"), port.log)
        assertEquals(1, reminders)
        assertEquals(Takeover.Stage.Waiting, t.stage.value)
    }
}
