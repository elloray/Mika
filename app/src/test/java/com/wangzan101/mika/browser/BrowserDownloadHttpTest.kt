package com.wangzan101.mika.browser

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserDownloadHttpTest {
    @Test fun cookiesAreLookedUpOnSameOriginAndStrippedOnCrossOriginRedirect() {
        MockWebServer().use { origin ->
            MockWebServer().use { destination ->
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/another-path"))
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/file")))
                destination.enqueue(MockResponse().setBody("download"))
                val client = browserDownloadClient(OkHttpClient()) { url ->
                    if (url.endsWith("/another-path")) "same-origin=allowed" else "session=private"
                }
                client.newCall(Request.Builder().url(origin.url("/start")).build()).execute().use {
                    assertEquals("download", it.body?.string())
                }
                assertEquals("session=private", origin.takeRequest().getHeader("Cookie"))
                assertEquals("same-origin=allowed", origin.takeRequest().getHeader("Cookie"))
                assertNull(destination.takeRequest().getHeader("Cookie"))
            }
        }
    }
}
