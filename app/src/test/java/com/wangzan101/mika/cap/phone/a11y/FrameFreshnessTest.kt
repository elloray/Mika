package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 假时钟:每次「睡」都直接把时钟推过去,等待循环同步走完,不需要协程测试框架。 */
private class FakeClock(start: Long = 1_000L) {
    var now = start
        private set
    val slept = mutableListOf<Long>()
    val sleep: suspend (Long) -> Unit = { ms -> slept += ms; now += ms }
    fun advance(ms: Long) { now += ms }
}

class FrameFreshnessTest {
    private fun clock(frameAt: Long?) = FakeClock() to ({ frameAt } as () -> Long?)

    @Test
    fun awaitsNothingWhenTheFrameIsAlreadyNewer() {
        val (c, f) = clock(frameAt = 1_400L)
        runBlocking { assertTrue(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L)) }
        // 一次都没睡:已经在手上的新帧不白等
        assertTrue(c.slept.isEmpty())
    }

    @Test
    fun timesOutWhenNoFrameEverComes() {
        val (c, f) = clock(frameAt = null)
        runBlocking { assertFalse(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L, timeoutMs = 1_500L)) }
        assertTrue(c.now >= 1_000L + 1_500L)
    }

    @Test
    fun timesOutWhenOnlyTheOldFrameExists() {
        val (c, f) = clock(frameAt = 900L)
        runBlocking { assertFalse(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L, timeoutMs = 1_500L)) }
    }

    @Test
    fun servingIsOpenWithoutALaunch() {
        val (c, f) = clock(frameAt = 900L)
        assertEquals(FrameFreshness.Serve.Fresh, FrameFreshness.serve(f, since = null))
    }

    @Test
    fun aNewerFrameServesFresh() {
        val (c, f) = clock(frameAt = 1_200L)
        assertEquals(FrameFreshness.Serve.Fresh, FrameFreshness.serve(f, since = 1_000L))
    }

    @Test
    fun aStaleFrameIsGuardedInsideTheWindow() {
        val (c, f) = clock(frameAt = 900L)
        // 启动后 1 秒,还没有新帧:不给旧帧
        c.advance(1_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f, since = 1_000L))
        // 没有过帧也一样拦着
        val (c2, f2) = clock(frameAt = null)
        c2.advance(1_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f2, since = 1_000L))
    }

    @Test
    fun anOldFrameNeverBecomesFreshMerelyBecauseTimePassed() {
        val (c, f) = clock(frameAt = 900L)
        c.advance(60_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f, since = 1_000L))
        // 只有这次启动之后的新帧能解除保护。
        val newer = FrameFreshness.serve({ 1_001L }, since = 1_000L)
        assertEquals(FrameFreshness.Serve.Fresh, newer)
    }
}
