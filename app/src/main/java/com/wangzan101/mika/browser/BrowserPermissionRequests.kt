package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.UUID

/** Identifies the WebView request across the asynchronous Android permission prompt. */
internal class BrowserPermissionRequests<T> {
    data class Pending<T>(val id: String, val origin: String, val value: T)

    private val current = MutableStateFlow<Pending<T>?>(null)
    val pending = current.asStateFlow()

    fun replace(origin: String, value: T): Pending<T>? {
        val request = Pending(UUID.randomUUID().toString(), origin, value)
        return current.getAndUpdate { request }
    }

    fun take(id: String): Pending<T>? {
        val request = current.value?.takeIf { it.id == id } ?: return null
        return request.takeIf { current.compareAndSet(it, null) }
    }
}
