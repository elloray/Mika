package com.wangzan101.mika.capsule

/**
 * 胶囊上的单击和双击(纯逻辑,时钟、落点和输入来源由调用方给):单击展开卡片,双击截取屏幕放进卡片预览。
 * 按下一下([tap])先不算单击:[timeoutMs] 内同一输入来源在 [slopPx] 内再点一下才是双击;
 * 到时还没有第二下([settle])才是单击。落点远、来源不同或时钟倒退都把这一下当作新的第一下。
 */
class DoubleTap(val timeoutMs: Long, private val slopPx: Float) {
    enum class Tap {
        /** 第一下:等 [timeoutMs] 再 [settle]。 */
        First,
        Double,
    }

    private data class Point(val at: Long, val x: Float, val y: Float, val source: Int)

    private var first: Point? = null

    fun tap(now: Long, x: Float, y: Float, source: Int): Tap {
        val previous = first
        val elapsed = previous?.let { now - it.at }
        val samePlace = previous != null && run {
            val dx = x - previous.x
            val dy = y - previous.y
            dx * dx + dy * dy <= slopPx * slopPx
        }
        return if (previous != null && elapsed != null && elapsed in 0..timeoutMs && samePlace && source == previous.source) {
            first = null
            Tap.Double
        } else {
            first = Point(now, x, y, source)
            Tap.First
        }
    }

    /** 第一下之后到时了:还在等第二下就是单击(返回 true,只返回一次);已经成了双击或还没到时返回 false。 */
    fun settle(now: Long): Boolean {
        val previous = first ?: return false
        if (now - previous.at < timeoutMs) return false
        first = null
        return true
    }

    /** 不再等(清提示、拖动、长按、胶囊收起或换成别的样子)。 */
    fun reset() {
        first = null
    }
}
