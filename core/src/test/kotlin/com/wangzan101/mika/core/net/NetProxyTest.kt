package com.wangzan101.mika.core.net

import com.wangzan101.mika.core.agent.RetryPolicy
import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI

/** Mika 自己连接用的网络代理:按设置走代理、本机和局域网直连、HTTP 代理的账号密码、出错时说清是代理的问题。 */
class NetProxyTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    /** 一个肯定连不上的本机端口。 */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun parsesAddressesAndNeverShowsCredentials() {
        val p = ProxyAddress.parse(" http://alice:s3cret@10.0.0.2:8080 ")!!
        assertEquals("http", p.scheme)
        assertEquals("10.0.0.2", p.host)
        assertEquals(8080, p.port)
        assertEquals("alice", p.user)
        assertEquals("s3cret", p.password)
        assertEquals("http://10.0.0.2:8080", p.display)
        assertFalse(p.toString().contains("s3cret"))
        assertEquals(Proxy.Type.HTTP, p.javaProxy().type())

        val v6 = ProxyAddress.parse("socks5h://u:p@[::1]:1080")!!
        assertEquals("::1", v6.host)
        assertEquals("socks5h://[::1]:1080", v6.display)
        assertEquals(Proxy.Type.SOCKS, v6.javaProxy().type())
        assertTrue(v6.forApp)

        assertFalse("https 代理只有容器用得上", ProxyAddress.parse("https://proxy.example.com:443")!!.forApp)
        for (bad in listOf("", "127.0.0.1:7890", "ftp://h:21", "http://host", "http://h:80/path", "http://h :80")) assertNull(bad, ProxyAddress.parse(bad))
    }

    @Test fun localAndLanHostsAreRecognized() {
        for (h in listOf("localhost", "LOCALHOST", "127.0.0.1", "10.1.2.3", "172.20.0.1", "192.168.1.5", "169.254.1.1", "100.100.1.1",
            "::1", "[::1]", "fe80::1", "fd12:3456::1", "nas", "printer.local", "box.lan", "router.home.arpa", "svc.internal")) {
            assertTrue(h, LocalHosts.bypassesProxy(h))
        }
        for (h in listOf("api.openai.com", "8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:db8::1", "generativelanguage.googleapis.com")) {
            assertFalse(h, LocalHosts.bypassesProxy(h))
        }
        // 判断明文 http 安不安全用的那一套仍然严格:机器名、.lan 不算本机
        assertFalse(LocalHosts.isLocal("nas"))
        assertFalse(LocalHosts.isLocal("box.lan"))
    }

    @Test fun selectorSendsRemoteHostsThroughTheProxyAndKeepsLanDirect() {
        var setting = "socks5://127.0.0.1:1080"
        val net = NetProxy { setting }
        val remote = net.selector.select(URI("https://api.example.com/v1"))
        assertEquals(Proxy.Type.SOCKS, remote.single().type())
        assertEquals(listOf(Proxy.NO_PROXY), net.selector.select(URI("http://192.168.1.20:11434/v1")))
        assertEquals(listOf(Proxy.NO_PROXY), net.selector.select(URI("http://localhost:1234/v1")))

        // 改了设置马上按新的走
        setting = "http://10.0.0.2:8080"
        assertEquals(Proxy.Type.HTTP, net.selector.select(URI("https://api.example.com/v1")).single().type())
        setting = ""
        assertEquals(listOf(Proxy.NO_PROXY), net.selector.select(URI("https://api.example.com/v1")))
        // https 代理 Mika 自己的连接用不上:直连
        setting = "https://proxy.example.com:443"
        assertEquals(listOf(Proxy.NO_PROXY), net.selector.select(URI("https://api.example.com/v1")))
    }

    @Test fun requestsGoThroughHttpProxyWithCredentialsOnChallenge() = runTest {
        val net = NetProxy { "http://alice:s3cret@${server.hostName}:${server.port}" }
        server.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"proxy\""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val http = net.client()
        val raw = HttpSupport.execute(http, Request.Builder().url("http://api.example.test/v1/models").build())
        assertEquals(200, raw.status)

        val first = server.takeRequest()
        assertEquals("GET http://api.example.test/v1/models HTTP/1.1", first.requestLine)
        assertNull(first.getHeader("Proxy-Authorization"))
        val second = server.takeRequest()
        assertEquals("GET http://api.example.test/v1/models HTTP/1.1", second.requestLine)
        assertEquals(Credentials.basic("alice", "s3cret"), second.getHeader("Proxy-Authorization"))
    }

    @Test fun streamingThroughTheProxyStillArrivesEventByEvent() = runTest {
        val net = NetProxy { "http://${server.hostName}:${server.port}" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: a\n\ndata: b\n\n"))
        val events = mutableListOf<String>()
        HttpSupport.stream(net.client(), Request.Builder().url("http://api.example.test/v1/chat").build()) { event ->
            events += event
            false
        }
        assertEquals(listOf("a", "b"), events)
        assertEquals("GET http://api.example.test/v1/chat HTTP/1.1", server.takeRequest().requestLine)
    }

    @Test fun lanHostsBypassTheProxy() = runTest {
        // 代理指向连不上的端口;本机的服务照样连得上
        val net = NetProxy { "http://alice:pw@127.0.0.1:${closedPort()}" }
        server.enqueue(MockResponse().setBody("ok"))
        val raw = HttpSupport.execute(net.client(), Request.Builder().url(server.url("/v1/models")).build())
        assertEquals("ok", raw.body)
        val recorded = server.takeRequest()
        assertEquals("GET /v1/models HTTP/1.1", recorded.requestLine)
        assertNull(recorded.getHeader("Proxy-Authorization"))
    }

    @Test fun rejectedCredentialsAreAProxyAuthFailure() = runTest {
        val net = NetProxy { "http://alice:wrong@${server.hostName}:${server.port}" }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"proxy\"")) }
        try {
            HttpSupport.execute(net.client(), Request.Builder().url("http://api.example.test/v1/models").build())
            fail("expected a proxy error")
        } catch (e: ModelError.Proxy) {
            assertEquals(ProxyFailure.AuthFailed, e.failure)
            assertFalse("账号密码不对不重试", RetryPolicy.transient(e))
            assertEquals("proxy_auth", RetryPolicy.label(e))
        }
        // 带过一次账号密码还被拒就不再试
        assertEquals(2, server.requestCount)
    }

    @Test fun unreachableProxyIsSaidSoAndDirectFailuresStayNetworkErrors() = runTest {
        val port = closedPort()
        val net = NetProxy { "http://127.0.0.1:$port" }
        try {
            HttpSupport.execute(net.client(), Request.Builder().url("https://api.example.test/v1/models").build())
            fail("expected a proxy error")
        } catch (e: ModelError.Proxy) {
            assertEquals(ProxyFailure.Unreachable, e.failure)
            assertTrue(RetryPolicy.transient(e))
        }
        // 没走代理的连接失败照旧是网络错误
        try {
            HttpSupport.execute(OkHttpClient(), Request.Builder().url("http://127.0.0.1:$port/v1").build())
            fail("expected a network error")
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Network)
        }
    }

    @Test fun modelListSaysWhenTheProxyIsTheProblem() = runTest {
        val net = NetProxy { "http://127.0.0.1:${closedPort()}" }
        val r = ModelCatalog(net.client()).openAiCompat("https://api.example.test/v1", "k", emptyMap())
        assertEquals(ListFailure.ProxyUnreachable, (r as ModelListResult.Failed).reason)
        assertEquals(ListFailure.ProxyAuth, ListFailure.of(ModelError.Proxy(ProxyFailure.AuthFailed)))
        assertEquals(ListFailure.Network, ListFailure.of(ModelError.Network()))
    }

    @Test fun failuresAreClassified() {
        assertEquals(ProxyFailure.AuthFailed, NetProxy.classify(IOException("Failed to authenticate with proxy")))
        assertEquals(ProxyFailure.AuthFailed, NetProxy.classify(SocketException("SOCKS : authentication failed")))
        assertEquals(ProxyFailure.Unreachable, NetProxy.classify(IOException("Unexpected response code for CONNECT: 403")))
        assertEquals(ProxyFailure.Unreachable, NetProxy.classify(SocketException("Malformed reply from SOCKS server")))
        assertEquals(ProxyFailure.Unreachable, NetProxy.classify(ConnectException("Failed to connect to /127.0.0.1:7890")))
        assertEquals(ProxyFailure.Unreachable, NetProxy.classify(SocketTimeoutException("connect timed out")))
        assertNull("读回复时超时不是代理的问题", NetProxy.classify(SocketTimeoutException("timeout")))
        assertNull(NetProxy.classify(IOException("unexpected end of stream")))
    }

    @Test fun socksCredentialsOnlyForTheConfiguredProxy() {
        var setting = "socks5://bob:pw@10.0.0.9:1080"
        val net = NetProxy { setting }
        val pa = net.socksCredentials("SOCKS5", "10.0.0.9", 1080)!!
        assertEquals("bob", pa.userName)
        assertEquals("pw", String(pa.password))
        assertNull("别的主机不答", net.socksCredentials("SOCKS5", "10.0.0.8", 1080))
        assertNull("别的端口不答", net.socksCredentials("SOCKS5", "10.0.0.9", 1081))
        assertNull("不是 SOCKS 的询问不答", net.socksCredentials("http", "10.0.0.9", 1080))
        setting = "http://bob:pw@10.0.0.9:1080"
        assertNull("HTTP 代理不经这里给", net.socksCredentials("SOCKS5", "10.0.0.9", 1080))
    }

    @Test fun onlyClientsBuiltHereAreConsideredProxied() {
        val net = NetProxy { "http://10.0.0.2:8080" }
        assertTrue(NetProxy.of(net.client()) === net)
        assertNull(NetProxy.of(OkHttpClient()))
    }
}
