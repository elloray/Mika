package com.wangzan101.mika.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyAddressTest {
    @Test fun encodedColonStaysInSocksUsername() {
        for (scheme in listOf("socks5", "socks5h")) {
            val proxy = ProxyAddress.parse("$scheme://team%3Aalice:secret@proxy.example:1080")!!
            assertEquals("team:alice", proxy.user)
            assertEquals("secret", proxy.password)
        }
    }

    @Test fun encodedColonDoesNotIntroduceAPassword() {
        val proxy = ProxyAddress.parse("socks5://team%3Aalice@proxy.example:1080")!!
        assertEquals("team:alice", proxy.user)
        assertEquals("", proxy.password)
    }

    @Test fun credentialsAreDecodedOnceAndKeepLiteralPlusSigns() {
        val proxy = ProxyAddress.parse("socks5://team+alice%40example.com:p%3Ass+word%2B%25%253A@proxy.example:1080")!!
        assertEquals("team+alice@example.com", proxy.user)
        assertEquals("p:ss+word+%%3A", proxy.password)
    }
}
