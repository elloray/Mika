package com.wangzan101.mika.device

import kotlin.math.abs

/** 没有密码输入节点时,用完整且空间一致的 3×4 数字键盘识别系统 PIN 页。 */
internal object KeyguardPinPad {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DigitKey(val digit: Char, val bounds: Bounds)

    private data class Key(val x: Long, val y: Long, val width: Long, val height: Long)

    fun matches(keys: List<DigitKey>, window: Bounds): Boolean {
        if (!valid(window)) return false
        val grouped = keys.groupBy(DigitKey::digit)
        if (grouped.keys.any { it !in '0'..'9' }) return false
        val digits = mutableMapOf<Char, Key>()
        for (digit in '0'..'9') {
            val candidates = grouped[digit].orEmpty()
            if (candidates.isEmpty()) return false
            val validKeys = candidates.map { key ->
                if (!valid(key.bounds) || !inside(key.bounds, window)) return false
                key.normalized()
            }
            // 同一个键可能同时暴露可点击父节点和文字子节点;同心节点只算一个键。
            if (validKeys.map { it.x to it.y }.distinct().size != 1) return false
            digits[digit] = validKeys.minBy { it.width + it.height }
        }

        val rows = listOf("123", "456", "789")
        val columns = (0..2).map { column -> rows.sumOf { digits.getValue(it[column]).x } / rows.size }
        val rowY = rows.map { row -> row.sumOf { digits.getValue(it).y } / row.length }
        val widths = digits.values.map(Key::width).sorted()
        val heights = digits.values.map(Key::height).sorted()
        val xTolerance = (widths[widths.size / 2] / 4).coerceAtLeast(1)
        val yTolerance = (heights[heights.size / 2] / 4).coerceAtLeast(1)

        for ((rowIndex, row) in rows.withIndex()) {
            for ((column, digit) in row.withIndex()) {
                val key = digits.getValue(digit)
                if (abs(key.x - columns[column]) > xTolerance || abs(key.y - rowY[rowIndex]) > yTolerance) return false
            }
        }
        val columnGaps = columns.zipWithNext { left, right -> right - left }
        val rowGaps = rowY.zipWithNext { top, bottom -> bottom - top }
        if (!coherent(columnGaps, xTolerance) || !coherent(rowGaps, yTolerance)) return false

        val zero = digits.getValue('0')
        if (abs(zero.x - columns[1]) > xTolerance) return false
        val lastGap = zero.y - rowY.last()
        val minGap = rowGaps.min()
        val maxGap = rowGaps.max()
        return lastGap > yTolerance * 2 && lastGap >= minGap / 2 && lastGap <= maxGap * 2
    }

    private fun DigitKey.normalized() = Key(
        bounds.left.toLong() + (bounds.right.toLong() - bounds.left) / 2,
        bounds.top.toLong() + (bounds.bottom.toLong() - bounds.top) / 2,
        bounds.right.toLong() - bounds.left,
        bounds.bottom.toLong() - bounds.top,
    )

    private fun valid(bounds: Bounds): Boolean = bounds.right > bounds.left && bounds.bottom > bounds.top

    private fun inside(key: Bounds, window: Bounds): Boolean = key.left >= window.left && key.top >= window.top &&
        key.right <= window.right && key.bottom <= window.bottom

    private fun coherent(gaps: List<Long>, tolerance: Long): Boolean = gaps.all { it > tolerance * 2 } &&
        gaps.max() <= gaps.min() * 2
}
