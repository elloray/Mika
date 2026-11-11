package com.wangzan101.mika.core.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代开地址的规则:只 http/https,不要账号密码,长度有限;与 web_navigate 同一条基础规则。 */
class SelfUrlPolicyTest {
    @Test fun acceptsWebUrls() {
        assertNull(SelfUrlPolicy.check("https://example.com/a?q=1"))
        assertNull(SelfUrlPolicy.check("http://example.com/"))
    }

    @Test fun refusesNonWebSchemes() {
        for (url in listOf(
            "javascript:alert(1)", "file:///etc/passwd", "content://x/y", "intent://x#Intent;package=a.b;end",
            "data:text/html,hi", "about:blank", "example.com", "",
        )) {
            assertNotNull(url, SelfUrlPolicy.check(url))
        }
    }

    @Test fun refusesCredentialsAndLongUrls() {
        assertTrue(SelfUrlPolicy.check("https://user:pass@example.com/")!!.contains("username or password"))
        assertNotNull(SelfUrlPolicy.check("https://example.com/" + "a".repeat(SelfUrlPolicy.MAX_URL)))
    }

    @Test fun hostIsLowerCaseWithoutWww() {
        assertEquals("example.com", SelfUrlPolicy.host("https://www.Example.com/x"))
    }
}
