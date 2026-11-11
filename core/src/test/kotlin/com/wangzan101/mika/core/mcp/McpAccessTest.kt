package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.RemoteOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class McpAccessTest {
    private var now = 0L
    private val access = RemoteAccess({ TOKEN }, { now })
    private val pass = RemoteAccess.Verdict.Pass(RemoteOwnership.of(TOKEN))

    private fun req(headers: Map<String, String> = emptyMap(), path: String = "/mcp", from: String = "192.168.1.20", source: String? = null) =
        HttpRequest("POST", path, headers.mapKeys { it.key.lowercase() }, ByteArray(0), InetAddress.getByName(from),
            source = source ?: InetAddress.getByName(from).hostAddress)

    private fun status(v: RemoteAccess.Verdict) = (v as? RemoteAccess.Verdict.Reject)?.status ?: 200

    @Test fun tokenIsRequired() {
        assertEquals(401, status(access.check(req())))
        assertEquals(401, status(access.check(req(mapOf("Authorization" to "Bearer wrong")))))
        assertEquals(401, status(access.check(req(mapOf("Authorization" to TOKEN)))))
        assertEquals(401, status(access.check(req(mapOf("Authorization" to "Basic $TOKEN")))))
        val r = access.check(req(mapOf("Authorization" to "Bearer $TOKEN")))
        assertEquals(pass, r)
        assertEquals(pass, access.check(req(mapOf("Authorization" to "bearer $TOKEN"))))
        assertEquals("Bearer", (access.check(req()) as RemoteAccess.Verdict.Reject).headers["WWW-Authenticate"])
    }

    @Test fun noTokenConfiguredRejectsEverything() {
        val none = RemoteAccess({ null })
        assertEquals(401, status(none.check(req(mapOf("Authorization" to "Bearer ")))))
        assertEquals(401, status(none.check(req(mapOf("Authorization" to "Bearer null")))))
    }

    @Test fun browserOriginsAreRejectedEvenWithTheToken() {
        assertEquals(403, status(access.check(req(mapOf("Authorization" to "Bearer $TOKEN", "Origin" to "http://192.168.1.20:8765")))))
        assertEquals(403, status(access.check(req(mapOf("Origin" to "null")))))
    }

    @Test fun authenticationIsIndependentOfRouting() {
        assertEquals(401, status(access.check(req(path = "/"))))
        assertEquals(200, status(access.check(req(mapOf("Authorization" to "Bearer $TOKEN"), path = "/other"))))
    }

    @Test fun repeatedFailuresLockThatAddressForAWhile() {
        repeat(4) { assertEquals(401, status(access.check(req(mapOf("Authorization" to "Bearer guess$it"))))) }
        assertEquals(401, status(access.check(req(mapOf("Authorization" to "Bearer guess5")))))
        // 锁住之后连对的令牌也先不看
        val locked = access.check(req(mapOf("Authorization" to "Bearer $TOKEN")))
        assertEquals(429, status(locked))
        assertEquals("60", (locked as RemoteAccess.Verdict.Reject).headers["Retry-After"])
        // 别的地址不受影响
        assertEquals(pass, access.check(req(mapOf("Authorization" to "Bearer $TOKEN"), from = "192.168.1.21")))
        now += 61_000
        assertEquals(pass, access.check(req(mapOf("Authorization" to "Bearer $TOKEN"))))
    }

    @Test fun relayedCallersCoolDownSeparatelyAndALockedCallerLearnsNothing() {
        val relay = RemoteAccess({ TOKEN }, { now })
        val guesser = "relay|203.0.113.9"; val owner = "relay|198.51.100.7"
        repeat(5) { assertEquals(401, status(relay.check(req(from = "0.0.0.0", source = guesser)))) }
        // 冷却中连正确令牌也是 429:放行就会把「猜对了」告诉猜的人
        assertEquals(429, status(relay.check(req(mapOf("Authorization" to "Bearer $TOKEN"), from = "0.0.0.0", source = guesser))))
        assertEquals(pass, relay.check(req(mapOf("Authorization" to "Bearer $TOKEN"), from = "0.0.0.0", source = owner)))
        assertEquals(403, status(relay.check(req(mapOf("Authorization" to "Bearer $TOKEN", "Origin" to "https://example.test"), from = "0.0.0.0", source = owner))))
    }

    @Test fun failuresSpreadOutDoNotLock() {
        repeat(10) {
            assertEquals(401, status(access.check(req(mapOf("Authorization" to "Bearer guess")))))
            now += 20_000
        }
        assertEquals(pass, access.check(req(mapOf("Authorization" to "Bearer $TOKEN"))))
    }

    @Test fun constantTimeCompare() {
        assertTrue(RemoteAccess.same("abc", "abc"))
        assertFalse(RemoteAccess.same("abc", "abd"))
        assertFalse(RemoteAccess.same("abc", "abcd"))
    }

    @Test fun onlyPrivateAddressesAreServed() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.5", "100.64.0.1", "100.127.1.1",
            "169.254.1.1", "::1", "fd7a:115c:a1e0::1", "fe80::1", "::ffff:192.168.1.2").forEach {
            assertTrue(it, RemoteAccess.privateAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "1.1.1.1", "2001:4860:4860::8888", "::ffff:8.8.8.8").forEach {
            assertFalse(it, RemoteAccess.privateAddress(InetAddress.getByName(it)))
        }
    }

    private companion object {
        const val TOKEN = "s3cret-token-value"
    }
}
