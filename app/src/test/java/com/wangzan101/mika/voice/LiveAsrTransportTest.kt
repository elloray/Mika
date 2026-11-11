package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** WebSocket 连接:请求头只带给它的那些,文字和二进制回应都按文字读,握手被拒不带出服务端原话。只连本机的 MockWebServer。 */
class LiveAsrTransportTest {
    private fun withServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) = runBlocking {
        val server = MockWebServer(); val client = OkHttpClient()
        try { block(server, client) } finally {
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
        }
    }

    @Test fun sendsHeadersTextAndAudioAndReadsBothFrameKinds() = withServer { server, client ->
        val received = mutableListOf<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += "text:$text"
                webSocket.send("""{"event": "started"}""")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received += "audio:${bytes.size}"
                webSocket.send("""{"sentences": [], "partial": "好", "is_final": false}""".encodeUtf8())
            }
        }))
        val socket = withTimeout(2_000) {
            LiveAsrTransport(client).connect(server.url("/asr").toString().replace("http", "ws"), mapOf("Authorization" to "Bearer test-only"))
        }
        assertEquals("Bearer test-only", server.takeRequest().getHeader("Authorization"))
        socket.sendText("START")
        assertEquals("""{"event": "started"}""", withTimeout(2_000) { socket.incoming.receive() })
        socket.sendAudio(ByteArray(3200))
        assertEquals("""{"sentences": [], "partial": "好", "is_final": false}""", withTimeout(2_000) { socket.incoming.receive() })
        assertEquals(listOf("text:START", "audio:3200"), received)
        socket.close()
    }

    @Test fun noHeadersUnlessGiven() = withServer { server, client ->
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val socket = withTimeout(2_000) { LiveAsrTransport(client).connect(server.url("/").toString()) }
        assertNull(server.takeRequest().getHeader("Authorization"))
        socket.close()
    }

    @Test fun rejectedHandshakeIsSanitized() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("private upstream error must not be displayed"))
        val error = try {
            withTimeout(2_000) { LiveAsrTransport(client).connect(server.url("/").toString(), mapOf("Authorization" to "Bearer bad")) }
            null
        } catch (e: ModelError.Auth) { e }
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("private upstream"))
    }
}
