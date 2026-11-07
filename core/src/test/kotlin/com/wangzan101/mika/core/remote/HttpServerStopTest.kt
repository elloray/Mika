package com.wangzan101.mika.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class HttpServerStopTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun server() = MiniHttpServer(InetSocketAddress(loopback, 0), handler = { HttpResponse.empty(200) })

    private fun request(socket: Socket) {
        socket.soTimeout = 2_000
        socket.getOutputStream().write(
            "GET /api/v1 HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII),
        )
        // 收到响应后,服务端已经把这个连接登记到正在处理的集合中。
        assertEquals('H'.code, socket.getInputStream().read())
    }

    @Test fun stoppingWhileClientsDisconnectDoesNotThrow() {
        // 客户端关闭后,连接线程移除登记与 stop 同时执行;重复实际连接以覆盖这个交错。
        repeat(2_000) {
            val server = server()
            server.start()
            try {
                Socket(loopback, server.port).use(::request)
            } finally {
                server.stop()
            }
        }
    }

    @Test fun stoppingClosesEveryOpenConnectionAndCanBeRepeated() {
        val server = server()
        val clients = mutableListOf<Socket>()
        server.start()
        try {
            repeat(3) {
                val socket = Socket(loopback, server.port)
                clients += socket
                request(socket)
            }
            server.stop()
            assertEquals(-1, server.port)
            clients.forEach { socket ->
                val rest = socket.getInputStream().bufferedReader().readText()
                assertTrue(rest, rest.startsWith("TTP/1.1 200 "))
                assertTrue(rest, rest.endsWith("\r\n\r\n"))
            }
            server.stop()
        } finally {
            server.stop()
            clients.forEach { it.close() }
        }
    }
}
