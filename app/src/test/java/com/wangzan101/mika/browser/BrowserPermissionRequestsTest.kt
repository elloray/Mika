package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPermissionRequestsTest {
    @Test fun oldSystemResultCannotConsumeANewerWebRequest() {
        val requests = BrowserPermissionRequests<String>()
        requests.replace("https://original.example", "camera")
        val original = requireNotNull(requests.pending.value)
        assertEquals(original, requests.replace("https://frame.example:8443", "microphone"))

        assertNull(requests.take(original.id))
        val current = requireNotNull(requests.pending.value)
        assertEquals("https://frame.example:8443", current.origin)
        assertEquals("microphone", requests.take(current.id)?.value)
        assertNull(requests.take(current.id))
    }
}
