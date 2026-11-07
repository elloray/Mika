package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {
    private class FakeLock : WakeLockPort {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }

    @Test
    fun holdsWhileRunningAndReleasesAtEnd() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun waitingKeepsScreenOnForGraceThenReleases() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.WaitingTakeover("login", "请登录"))
        advanceTimeBy(KeepAwake.GRACE_MS - 1)
        runCurrent()
        assertTrue(lock.held)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(lock.held)

        // 用户处理完、任务继续跑:重新常亮
        awake.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
    }

    @Test
    fun resumingWithinGraceKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(TaskGate.paused("x")))
        advanceTimeBy(KeepAwake.GRACE_MS / 2)
        awake.onState(TaskState.Running(2, ""))
        advanceTimeBy(KeepAwake.GRACE_MS)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Cancelled)
        assertFalse(lock.held)
    }

    // 排在别的任务后面等虚拟屏:还在干活,不按等你处理宽限后放开
    @Test
    fun queuedForDisplayKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        assertTrue(lock.held)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        advanceTimeBy(KeepAwake.GRACE_MS * 2)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Running(2, ""))
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun failureAndCancelRelease() = runTest {
        for (end in listOf(TaskState.Failed("x", FailKind.GaveUp), TaskState.Cancelled)) {
            val lock = FakeLock()
            val awake = KeepAwake(lock, backgroundScope)
            awake.onState(TaskState.Running(1, ""))
            awake.onState(end)
            assertFalse(lock.held)
        }
    }

    @Test
    fun noReacquireAfterClose() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.close()
        assertFalse(lock.held)
        // 任务结束后迟到的状态发布不能把锁重新拿回来
        awake.onState(TaskState.Running(2, ""))
        assertFalse(lock.held)
    }

    // 两个任务各持一份:第一个开始时拿锁,最后一个结束时才放
    @Test
    fun refCountedAcrossRuns() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        val b = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        b.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        a.onState(TaskState.Succeeded("ok"))
        assertTrue(lock.held)
        a.close()
        assertTrue(lock.held)
        b.onState(TaskState.Cancelled)
        assertFalse(lock.held)
        b.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    // 排队的任务接班:前一个收尾时先替它拿一份再放开自己的,接班的拿上自己那份后放开,中间屏幕锁不断
    @Test
    fun handoffBridgesToQueuedRun() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        val handoff = shared.lease().also { it.acquire() }
        a.close()
        assertTrue(lock.held)
        val b = KeepAwake(shared.lease(), backgroundScope)
        b.onState(TaskState.Running(0, ""))
        handoff.release()
        assertTrue(lock.held)
        // 重复放开(开跑时放过、协程结束时又放一次)不多算
        handoff.release()
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    /** 屏幕只保持开着,不锁在全亮:用户不操作时由系统照常调暗。 */
    @Suppress("DEPRECATION")
    @Test
    fun screenLockLetsTheSystemDim() {
        assertEquals(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, ScreenWakeLock.LEVEL)
    }
}
