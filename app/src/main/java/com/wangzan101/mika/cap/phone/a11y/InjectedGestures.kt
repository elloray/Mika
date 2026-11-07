package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 自有注入手势期间隔离浮层触摸;计数和 [isolate] 回调只在主线程使用。 */
internal class InjectedGestures(private val isolate: (Boolean) -> Unit) {
    private var depth = 0
    val isolated: Boolean get() = depth > 0

    suspend fun <T> passThrough(block: suspend () -> T): T {
        var entered = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                depth++
                // 返回调用线程时可能立即因取消抛错;从修改计数起就必须由 finally 成对恢复。
                entered = true
                if (depth == 1) isolate(true)
            }
            return withContext(NonCancellable) { block() }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // 排队等主线程时就被取消的没有进入隔离,不能减掉其他手势的计数。
            if (entered) try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    depth = (depth - 1).coerceAtLeast(0)
                    if (depth == 0) isolate(false)
                }
            } catch (restoration: Throwable) {
                // 清理失败不能盖住取消或手势、窗口隔离原本的错误。
                val original = failure
                if (original == null) throw restoration
                if (original !== restoration) original.addSuppressed(restoration)
            }
        }
    }
}

/** 每个窗口都尝试更新;全部做完再抛首个错误,其余错误附加进去,避免一个坏窗口阻断后续恢复。 */
internal fun <T> forEachGestureWindow(windows: List<T>, update: (T) -> Unit) {
    var failure: Throwable? = null
    for (window in windows) {
        try {
            update(window)
        } catch (t: Throwable) {
            val first = failure
            if (first == null) failure = t else if (first !== t) first.addSuppressed(t)
        }
    }
    failure?.let { throw it }
}
