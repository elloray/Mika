package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** 经 USB 的电脑连接:只绑回环、回环来的才认 USB 令牌、连接信息文件和电脑上跑的脚本。 */
class McpUsbTest {
    private fun req(token: String?, from: String) = HttpRequest(
        "POST", "/mcp", token?.let { mapOf("authorization" to "Bearer $it") }.orEmpty(), ByteArray(0), InetAddress.getByName(from),
    )

    private fun status(v: RemoteAccess.Verdict) = (v as? RemoteAccess.Verdict.Reject)?.status ?: 200

    @Test fun usbModeBindsIpv4LoopbackOnly() {
        val a = RemoteHost.bindAddress(allowAllInterfaces = false, port = 8765)
        assertTrue(a.address is Inet4Address)
        assertEquals("127.0.0.1", a.address.hostAddress)
        assertEquals(8765, a.port)
        // 局域网那种绑所有网卡
        assertTrue(RemoteHost.bindAddress(allowAllInterfaces = true, port = 8765).address.isAnyLocalAddress)
    }

    @Test fun usbTokenIsOnlyAcceptedFromLoopback() {
        val access = RemoteAccess({ null }, loopbackToken = { USB })
        assertEquals(200, status(access.check(req(USB, "127.0.0.1"))))
        assertEquals(401, status(access.check(req(USB, "192.168.1.20"))))
        assertEquals(401, status(access.check(req("wrong", "127.0.0.1"))))
        assertEquals(401, status(access.check(req(null, "127.0.0.1"))))
    }

    @Test fun bothTokensWorkWhenBothModesAreOn() {
        val access = RemoteAccess({ NET }, loopbackToken = { USB })
        assertEquals(200, status(access.check(req(NET, "192.168.1.20"))))
        assertEquals(200, status(access.check(req(NET, "127.0.0.1"))))
        assertEquals(200, status(access.check(req(USB, "127.0.0.1"))))
        // USB 关了:令牌当场作废
        val off = RemoteAccess({ NET }, loopbackToken = { null })
        assertEquals(401, status(off.check(req(USB, "127.0.0.1"))))
    }

    @Test fun usbTaskOwnerSurvivesTokenRotation() {
        var usb = USB
        val access = RemoteAccess({ NET }, loopbackToken = { usb })
        fun owner(token: String, address: String): Pair<Int, String?> {
            var owner: String? = null
            val response = access.handle(req(token, address)) {
                owner = it.accessOwner
                HttpResponse(200)
            }
            return response.status to owner
        }

        val original = owner(USB, "127.0.0.1")
        usb = "replacement-usb-token"
        assertEquals(200, original.first)
        assertEquals(original, owner(usb, "127.0.0.1"))
        assertEquals(401 to null, owner(USB, "127.0.0.1"))
        assertEquals(401 to null, owner(usb, "192.168.1.20"))
        assertTrue(original.second != owner(NET, "127.0.0.1").second)
    }

    @Test fun loopbackHostAcceptsTheUsbTokenOverHttp() {
        val host = RemoteHost(FakeRemoteTasks(), { null }, port = 0, allowAllInterfaces = false, serverVersion = "test", loopbackToken = { USB })
        host.start()
        try {
            val client = OkHttpClient()
            fun post(token: String) = client.newCall(
                Request.Builder().url("http://127.0.0.1:${host.boundPort}/mcp")
                    .post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer $token").build(),
            ).execute().use { it.code }
            assertEquals(200, post(USB))
            assertEquals(401, post(NET))
        } finally {
            host.stop()
        }
    }

    @Test fun infoHasTheClientConfigAndTheBoot() {
        val text = McpUsb.info(8765, USB, "17")
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("http://127.0.0.1:8765/mcp", o["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer $USB", o["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertEquals(USB, o["token"]!!.jsonPrimitive.content)
        assertEquals("adb forward tcp:8765 tcp:8765", o["forward"]!!.jsonPrimitive.content)
        assertEquals("17", o["boot"]!!.jsonPrimitive.content)
        // 脚本按 "boot": "<编号>" 认这次开机写的文件
        assertTrue(Regex("\"boot\": *\"17\"").containsMatchIn(text))
    }

    @Test fun scriptOnlyRunsAsShellAndHoldsNoSecret() {
        val s = McpUsb.script(
            "/sdcard/Android/data/p/files/connect-computer.sh", "/storage/emulated/0/Android/data/p/files/usb-start.sh",
            "/storage/emulated/0/Android/data/p/files/mcp-usb.json", "p/p.mcp.McpService", "p.USB_ON", "p.USB_OFF", 8765,
        )
        assertTrue(s.startsWith("#!/system/bin/sh\n"))
        assertTrue(s.contains("if [ \"\$(id -u)\" != \"2000\" ]; then"))
        assertTrue(s.contains("am start-foreground-service -n 'p/p.mcp.McpService' -a 'p.USB_ON'"))
        assertTrue(s.contains("am start-foreground-service -n 'p/p.mcp.McpService' -a 'p.USB_OFF'"))
        assertTrue(s.contains("sh '/storage/emulated/0/Android/data/p/files/usb-start.sh'"))
        assertTrue(s.contains("settings get global boot_count"))
        assertTrue(s.contains("adb forward tcp:8765 tcp:8765"))
        assertTrue(s.contains("adb shell sh /sdcard/Android/data/p/files/connect-computer.sh"))
        assertFalse(s.contains("Bearer"))
    }

    private companion object {
        const val USB = "usb-token-0123456789"
        const val NET = "net-token-0123456789"
    }
}
