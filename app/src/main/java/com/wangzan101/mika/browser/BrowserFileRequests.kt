package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow

/** Associates an Android file picker result with the page request that opened it. */
internal fun <T> MutableStateFlow<T?>.takeFileRequest(id: String, requestId: (T) -> String): T? {
    val request = value?.takeIf { requestId(it) == id } ?: return null
    return request.takeIf { compareAndSet(it, null) }
}
