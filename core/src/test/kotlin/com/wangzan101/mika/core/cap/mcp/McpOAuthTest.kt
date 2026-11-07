package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTest {
    private fun serverUrl(server: MockWebServer) =
        server.url("/mcp").newBuilder().host("127.0.0.1").build()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun discoversAuthServerAndRegistersPublicClient() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"resource":"$base/mcp","authorization_servers":["$base"],"scopes_supported":["mcp","read"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}"""))
            server.enqueue(json("""{"client_id":"cid","client_secret":"sec"}"""))

            val oauth = McpOAuth(OkHttpClient())
            val meta = oauth.discover(serverUrl(server))
            assertEquals("$base/authorize", meta.authorizationEndpoint.toString())
            assertEquals("$base/token", meta.tokenEndpoint.toString())
            assertEquals("mcp read", meta.scope)
            val registration = oauth.register(meta, "http://127.0.0.1:7777/auth/callback")
            assertEquals("cid", registration.clientId)
            assertEquals("sec", registration.clientSecret)

            server.takeRequest()
            server.takeRequest()
            val post = server.takeRequest()
            assertEquals("/register", post.path)
            val body = post.body.readUtf8()
            assertTrue(body.contains("\"token_endpoint_auth_method\":\"none\""))
            assertTrue(body.contains("http://127.0.0.1:7777/auth/callback"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun authorizeUrlCarriesPkceStateAndResource() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer(
                authorizationEndpoint = "$base/authorize".toUrl(),
                tokenEndpoint = "$base/token".toUrl(),
                registrationEndpoint = "$base/register".toUrl(),
                resource = serverUrl(server),
                scope = "mcp",
            )
            val attempt = oauth.newAttempt("http://127.0.0.1:7777/auth/callback")
            val url = oauth.authorizeUrl(meta, McpOAuthRegistration("cid", null), attempt, meta.resource)
            assertTrue(url.contains("response_type=code"))
            assertTrue(url.contains("client_id=cid"))
            assertTrue(url.contains("code_challenge_method=S256"))
            assertTrue(url.contains("state=" + attempt.state))
            assertTrue(url.contains("code_challenge=" + attempt.challenge))
            assertTrue(url.contains("resource=http%3A%2F%2F127.0.0.1"))
            assertTrue(url.contains("scope=mcp"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun exchangesCodeAndRefreshesWithoutLosingRefreshToken() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"access_token":"at1","refresh_token":"rt1","expires_in":3600,"token_type":"Bearer"}"""))
            server.enqueue(json("""{"access_token":"at2","expires_in":3600}"""))
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer(
                authorizationEndpoint = "$base/authorize".toUrl(),
                tokenEndpoint = "$base/token".toUrl(),
                registrationEndpoint = null,
                resource = serverUrl(server),
            )
            val attempt = oauth.newAttempt("http://127.0.0.1:7777/auth/callback")
            val tokens = oauth.exchange(meta, McpOAuthRegistration("cid", null), "code1", attempt, meta.resource)
            assertEquals("at1", tokens.accessToken)
            assertEquals("rt1", tokens.refreshToken)
            assertTrue(tokens.isFresh())
            val first = server.takeRequest().body.readUtf8()
            assertTrue(first.contains("grant_type=authorization_code"))
            assertTrue(first.contains("code_verifier=" + attempt.verifier))

            val refreshed = oauth.refresh(tokens)
            assertEquals("at2", refreshed.accessToken)
            assertEquals("rt1", refreshed.refreshToken)
            val second = server.takeRequest().body.readUtf8()
            assertTrue(second.contains("grant_type=refresh_token"))
            assertTrue(second.contains("refresh_token=rt1"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun tokenErrorDoesNotLeakResponseBody() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(
                json("""{"error":"invalid_grant","error_description":"code expired: secret-detail"}""")
                    .setResponseCode(400),
            )
            val oauth = McpOAuth(OkHttpClient())
            val meta = McpOAuthServer("$base/authorize".toUrl(), "$base/token".toUrl(), null, null)
            val error = runCatching {
                oauth.exchange(meta, McpOAuthRegistration("cid", null), "bad", oauth.newAttempt("http://127.0.0.1:1/cb"), null)
            }.exceptionOrNull()
            assertTrue(error is McpOAuthException)
            assertTrue(error!!.message!!.contains("invalid_grant"))
            assertFalse(error.message!!.contains("secret-detail"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun discoversFromUnauthorizedChallenge() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Bearer resource_metadata=\"$base/prm\""),
            )
            server.enqueue(json("""{"resource":"$base/mcp","authorization_servers":["$base"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token"}"""))
            val meta = McpOAuth(OkHttpClient()).discover(serverUrl(server))
            assertEquals("$base/authorize", meta.authorizationEndpoint.toString())
        } finally {
            server.shutdown()
        }
    }

    @Test fun discoversAuthServerWhoseIssuerHasPath() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            // 主机根上那份是另一个 issuer 的,不能拿来用
            server.dispatcher = authServers(base, "$base/tenant1", mapOf(
                "/.well-known/oauth-authorization-server/tenant1" to "$base/tenant1",
                "/.well-known/oauth-authorization-server" to "$base/other",
            ))
            val meta = runCatching { McpOAuth(OkHttpClient()).discover(serverUrl(server)) }.getOrNull()
            assertEquals("$base/tenant1/authorize", meta?.authorizationEndpoint?.toString())
        } finally {
            server.shutdown()
        }
    }

    @Test fun discoversOpenIdConfigurationAppendedToIssuerPath() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.dispatcher = authServers(base, "$base/realms/demo/", mapOf(
                "/realms/demo/.well-known/openid-configuration" to "$base/realms/demo",
            ))
            val meta = runCatching { McpOAuth(OkHttpClient()).discover(serverUrl(server)) }.getOrNull()
            assertEquals("$base/realms/demo/token", meta?.tokenEndpoint?.toString())
        } finally {
            server.shutdown()
        }
    }

    @Test fun authServerMetadataUrlsFollowSpecOrder() = runBlocking {
        for ((issuerPath, expected) in listOf(
            "/tenant1/" to listOf(
                "/.well-known/oauth-authorization-server/tenant1",
                "/.well-known/openid-configuration/tenant1",
                "/tenant1/.well-known/openid-configuration",
            ),
            "/org/a%2Fb" to listOf(
                "/.well-known/oauth-authorization-server/org/a%2Fb",
                "/.well-known/openid-configuration/org/a%2Fb",
                "/org/a%2Fb/.well-known/openid-configuration",
            ),
            "" to listOf("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"),
        )) {
            val server = MockWebServer().also { it.start() }
            try {
                val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
                server.dispatcher = authServers(base, base + issuerPath, emptyMap())
                runCatching { McpOAuth(OkHttpClient()).discover(serverUrl(server)) }
                val paths = (1..server.requestCount).map { server.takeRequest().path }
                assertEquals(expected, paths.filterNot { it!!.contains("oauth-protected-resource") })
            } finally {
                server.shutdown()
            }
        }
    }

    /** 资源元数据指向 [issuer];[metadata] 是授权服务器元数据的路径 → 其中端点所在的 issuer,别的路径一律 404。 */
    private fun authServers(base: String, issuer: String, metadata: Map<String, String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path == "/.well-known/oauth-protected-resource") {
                return json("""{"resource":"$base/mcp","authorization_servers":["$issuer"]}""")
            }
            val at = metadata[path] ?: return MockResponse().setResponseCode(404)
            return json("""{"issuer":"$at","authorization_endpoint":"$at/authorize","token_endpoint":"$at/token"}""")
        }
    }

    @Test fun rejectsInsecureAuthEndpoint() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            server.enqueue(json("""{"authorization_servers":["$base"]}"""))
            server.enqueue(json("""{"authorization_endpoint":"http://example.com/authorize","token_endpoint":"$base/token"}"""))
            val error = runCatching { McpOAuth(OkHttpClient()).discover(serverUrl(server)) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is McpOAuthException)
        } finally {
            server.shutdown()
        }
    }

    private fun String.toUrl() = toHttpUrl()
}
