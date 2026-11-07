package com.wangzan101.mika.core.usage

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次逻辑模型调用里实际交给 HTTP 客户端的推理请求数。
 * 适配器内部的兼容重试和外层重试都经过同一计数点；只存在于当前调用的协程上下文，不跨调用累计。
 */
internal class ModelHttpAttempts : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelHttpAttempts>

    private val count = AtomicInteger()
    val started: Int get() = count.get()

    fun start() {
        count.incrementAndGet()
    }
}
