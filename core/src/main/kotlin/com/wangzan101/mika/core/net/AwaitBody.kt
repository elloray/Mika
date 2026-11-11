package com.wangzan101.mika.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 正文在 OkHttp 回调线程内读完再交回，等待响应和读取正文期间都能取消。 */
internal suspend fun <T> Call.awaitBody(timeoutSec: Int? = null, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        if (timeoutSec != null) timeout().timeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                val result = try {
                    response.use(read)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
