package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.web.KeptPage

/**
 * 当前 UI 状态会在 LazyColumn 里生成多少行。自动滚到末尾必须用这份当前状态算目标,不能读上一帧的
 * LazyListLayoutInfo.totalItemsCount:发送后的副作用可能先于新行测量执行,旧行数会把新轮次留在视口外。
 * 条件和 ChatScreen.exchange 及其附属行一一对应;末尾状态行即使暂时没有可见内容也占一个 LazyList item。
 */
internal fun chatItemCount(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupNeeded: Boolean,
    hasBlocked: Boolean,
): Int {
    var count = if (exchanges.isEmpty() && !hasBlocked) 1 else 0
    exchanges.forEach { ex ->
        if (ex.goal.isNotEmpty()) count++
        val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
        if (if (ex.status.isActive()) !answering else ex.steps.isNotEmpty() || ex.runId != null) count++
        val (joined, waiting) = steersAroundDraft(ex.steers)
        (joined + waiting).forEach { note ->
            if (!note.finished.isNullOrBlank()) count++
            if (!note.interrupted.isNullOrBlank()) count++
            count++
        }
        if (!ex.draft.isNullOrBlank()) count++
        count++ // StatusCard
        if (KeptRow.of(ex, kept) != null) count++
        if (ex.files.isNotEmpty()) count++
        if (ex.runId != null && (ex.memoryNotes.isNotEmpty() || ex.skillNotes.isNotEmpty())) count++
    }
    if (phoneSetupNeeded && exchanges.isNotEmpty()) count++
    if (hasBlocked) count++
    return count
}
