package com.wangzan101.mika.device

/** 生物识别优先的锁屏页(没有密码框、也没有数字键盘)需要一次「用密码解锁」的回退点击。 */
internal object KeyguardReveal {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height.toLong()
    }

    data class Candidate(val bounds: Bounds, val clickable: Boolean, val digit: Boolean)

    /**
     * 从 SystemUI 窗口的候选中挑一个最可能是「切换到凭据输入」的大面积点击目标:
     * 排除数字键和不可点节点,只留覆盖窗口 [minAreaFraction] 以上的大目标,取其中面积最大者。
     * 「使用密码」这类大按钮能被选中,而「取消」「紧急呼叫」这类小文字链接被排除。
     * 没有符合条件的目标时返回 null——宁可不动,也不乱点。
     */
    fun fallbackTarget(candidates: List<Candidate>, window: Bounds, minAreaFraction: Float): Bounds? {
        if (!valid(window)) return null
        val minArea = window.area * minAreaFraction
        return candidates
            .filter { it.clickable && !it.digit && valid(it.bounds) && inside(it.bounds, window) }
            .filter { it.bounds.area >= minArea }
            .maxByOrNull { it.bounds.area }
            ?.bounds
    }

    fun center(bounds: Bounds): Pair<Int, Int> = (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2

    private fun valid(b: Bounds): Boolean = b.right > b.left && b.bottom > b.top

    private fun inside(b: Bounds, w: Bounds): Boolean = b.left >= w.left && b.top >= w.top &&
        b.right <= w.right && b.bottom <= w.bottom
}
