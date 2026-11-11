package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserFileRequestsTest {
    @Test fun resultFromReplacedRequestLeavesTheNewRequestUntouched() {
        val requests = MutableStateFlow<String?>("first page")
        val first = requireNotNull(requests.value)
        requests.value = "second page"

        assertNull(requests.takeFileRequest(first) { it })
        assertEquals("second page", requests.value)
        assertEquals("second page", requests.takeFileRequest("second page") { it })
        assertNull(requests.takeFileRequest("second page") { it })
    }
}
