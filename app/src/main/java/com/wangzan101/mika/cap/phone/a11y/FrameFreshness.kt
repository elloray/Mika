package com.wangzan101.mika.cap.phone.a11y

/**
 * Agent 屏画面的新鲜度(帧时刻见 [AgentDisplay.lastFrameAt]):启动一个 App 之后交给模型的画面必须来自该次启动之后的渲染帧,
 * 比较虚拟屏生产者的时间戳和启动时记录的同源单调时钟（纳秒），避免放行启动前已入队、启动后才读取的旧帧。
 * 等待预算单独用毫秒计时。
 * 时钟、等待、最新帧时刻都由调用方注入,便于在 JVM 上测(同 [StillFrameRead])。
 */
internal object FrameFreshness {
    /** 启动收尾时等这次启动之后第一帧的上限:冷启动首帧、启动页动画绝大多数在这之内。 */
    const val FRAME_WAIT_MS = 1_500L
    const val POLL_MS = 60L

    /** 等到比 [since] 晚的帧返回 true;到 [timeoutMs] 仍没有(或一直没有过帧)返回 false。 */
    suspend fun awaitNewFrame(
        now: () -> Long, sleep: suspend (Long) -> Unit, frameAt: () -> Long?, since: Long, timeoutMs: Long = FRAME_WAIT_MS,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (true) {
            if ((frameAt() ?: 0L) > since) return true
            if (now() >= deadline) return false
            sleep(minOf(POLL_MS, deadline - now()))
        }
    }

    /** 此刻能不能把现有最新帧交给模型:[Fresh] 可以;[Guarded] 是启动后还没有新帧,不给旧帧。 */
    fun serve(frameAt: () -> Long?, since: Long?): Serve = when {
        since == null || (frameAt() ?: 0L) > since -> Serve.Fresh
        else -> Serve.Guarded
    }

    enum class Serve { Fresh, Guarded }
}
