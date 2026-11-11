package com.wangzan101.mika.core.self

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.AskPort
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自身工具:开关/来源的暴露矩阵、只读状态、文件路径、通知限流、剪贴板。 */
class SelfToolsTest {
    private class FakeSelf : SelfPort {
        var status = SelfStatus(
            appVersion = "1.2.3", appBuild = "abc", sdk = 35, mainModel = "Main",
            browserAvailable = true, sandbox = "ready",
        )
        var opened: SelfScreen? = null
        var openedFile: String? = null
        var notified = 0
        var copied: String? = null
        var browser = SelfBrowserState(pageOpen = true, host = "old.example.com", multiTab = false)
        var openedBrowser: String? = null
        var searches = mutableListOf<String>()
        var login: String? = null
        var confirmed = false
        var siteList = listOf(SelfSite("example.com", loggedIn = true))
        var favoriteList = listOf(SelfFavorite("example.com", loggedIn = true))
        var favoriteOp: String? = null
        var favoriteHost: String? = null

        override suspend fun status() = status
        override suspend fun openScreen(screen: SelfScreen, note: String?): SelfResult {
            opened = screen
            return SelfResult(ok = true, requested = true, foreground = true)
        }
        override suspend fun openFile(path: String, name: String?): SelfResult {
            openedFile = path
            return SelfResult(ok = true, requested = true, foreground = true)
        }
        override suspend fun notify(title: String, body: String, screen: SelfScreen?): SelfResult {
            notified++
            return SelfResult(ok = true, notified = true)
        }
        override suspend fun copy(text: String): Boolean {
            copied = text
            return true
        }
        override suspend fun browserState() = browser
        override suspend fun openInBrowser(url: String, show: Boolean, reason: String?): SelfResult {
            openedBrowser = url
            return SelfResult(ok = true, requested = true, foreground = true)
        }
        override suspend fun searchInBrowser(query: String, show: Boolean): SelfResult {
            searches += query
            return SelfResult(ok = true, requested = true, foreground = true)
        }
        override suspend fun sites() = siteList
        override suspend fun loginSite(host: String): SelfResult {
            login = host
            return SelfResult(ok = true, requested = true, foreground = true)
        }
        override suspend fun confirmLogin(): SelfResult {
            confirmed = true
            return SelfResult(ok = true, foreground = true)
        }
        override suspend fun favorites(op: String, host: String?): List<SelfFavorite> {
            favoriteOp = op
            favoriteHost = host
            return favoriteList
        }
        override fun browserConsent(url: String, query: String?, replaces: Boolean) =
            SelfConsent("open $url $query replaces=$replaces", "Open", "Don't open")
        override fun loginConsent(host: String, replaces: Boolean) =
            SelfConsent("login $host replaces=$replaces", "Open", "Don't open")
    }

    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun names(self: SelfPort?, operate: SelfPort?) =
        toolDispatcher(FakeScreen(), self = self, selfOperate = operate).definitions().map { it.name }

    @Test fun statusNeedsAPortButOperationsNeedTheSwitch() {
        assertFalse(names(null, null).any { it.startsWith("mika_") })
        val readOnly = names(FakeSelf(), null)
        assertTrue("mika_status" in readOnly)
        assertFalse(readOnly.any { it == "mika_open_screen" || it == "mika_open_file" || it == "mika_notify" || it == "mika_copy" })
        val all = names(FakeSelf(), FakeSelf())
        assertTrue(listOf("mika_status", "mika_open_screen", "mika_open_file", "mika_notify", "mika_copy").all { it in all })
    }

    @Test fun statusIsReadAndCarriesNoSecrets() = runTest {
        val d = toolDispatcher(FakeScreen(), self = FakeSelf())
        val out = d.executeRead(call("mika_status")).content.toString()
        assertTrue(out, out.contains("\"version\":\"1.2.3\""))
        assertTrue(out, out.contains("\"main\":\"Main\""))
        assertFalse(out, out.contains("token", ignoreCase = true) || out.contains("apiKey", ignoreCase = true))
    }

    @Test fun openScreenOpensTheEnumScreen() = runTest {
        val port = FakeSelf()
        val d = toolDispatcher(FakeScreen(), self = port, selfOperate = port)
        val out = d.execute(call("mika_open_screen", """{"screen":"settings"}""")).content
        assertTrue(out.toString(), out["ok"]!!.jsonPrimitive.content == "true")
        assertEquals(SelfScreen.Settings, port.opened)
    }

    @Test fun openFileStaysInsideWorkspace() = runTest {
        val port = FakeSelf()
        val d = toolDispatcher(FakeScreen(), self = port, selfOperate = port)
        val bad = d.execute(call("mika_open_file", """{"path":"/etc/passwd"}""")).content
        assertEquals("invalid_path", bad["error"]!!.jsonPrimitive.content)
        val good = d.execute(call("mika_open_file", """{"path":"/workspace/out/report.pdf"}""")).content
        assertEquals("true", good["ok"]!!.jsonPrimitive.content)
        assertEquals("/workspace/out/report.pdf", port.openedFile)
    }

    @Test fun notifyIsLimitedPerTask() = runTest {
        val port = FakeSelf()
        val d = toolDispatcher(FakeScreen(), self = port, selfOperate = port)
        repeat(3) {
            val out = d.execute(call("mika_notify", """{"title":"T","body":"B"}""")).content
            assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        }
        val fourth = d.execute(call("mika_notify", """{"title":"T","body":"B"}""")).content
        assertEquals("rate_limited", fourth["error"]!!.jsonPrimitive.content)
        assertEquals(3, port.notified)
    }

    @Test fun copyWritesOnly() = runTest {
        val port = FakeSelf()
        val d = toolDispatcher(FakeScreen(), self = port, selfOperate = port)
        val out = d.execute(call("mika_copy", """{"text":"hello"}""")).content
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals("hello", port.copied)
    }

    @Test fun statusJsonHasNoNullDumps() {
        val json = SelfStatus(appVersion = "1", mainModel = null).toJson().toString()
        assertFalse(json, json.contains("null"))
        assertTrue(json, json.contains("\"configured\":false"))
    }

    private fun browserDispatcher(port: FakeSelf, answer: String?): ToolDispatcher {
        val d = toolDispatcher(FakeScreen(), self = port, selfOperate = port)
        d.ctx.ask = object : AskPort {
            override suspend fun awaitAnswer(prompt: String, options: List<String>?): String? = answer
        }
        return d
    }

    @Test fun openInBrowserAsksThenOpens() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        val out = d.execute(call("mika_open_in_browser", """{"url":"https://example.com/a"}""")).content
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/a", port.openedBrowser)
    }

    @Test fun openInBrowserDoesNothingWhenTheUserDeclines() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Don't open")
        val out = d.execute(call("mika_open_in_browser", """{"url":"https://example.com/a"}""")).content
        assertEquals("declined", out["error"]!!.jsonPrimitive.content)
        assertNull(port.openedBrowser)
    }

    @Test fun openInBrowserRejectsBadUrlsBeforeAsking() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.com/")) {
            val out = d.execute(call("mika_open_in_browser", """{"url":"$url"}""")).content
            assertEquals(url, "invalid_url", out["error"]!!.jsonPrimitive.content)
        }
        assertNull(port.openedBrowser)
    }

    @Test fun openInBrowserRefusesABorrowedPage() = runTest {
        val port = FakeSelf().also { it.browser = it.browser.copy(loaned = true) }
        val d = browserDispatcher(port, "Open")
        val out = d.execute(call("mika_open_in_browser", """{"url":"https://example.com/a"}""")).content
        assertEquals("page_in_use", out["error"]!!.jsonPrimitive.content)
    }

    @Test fun browserActionsAreLimitedPerTask() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        repeat(5) {
            val out = d.execute(call("mika_open_in_browser", """{"url":"https://example.com/$it"}""")).content
            assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        }
        val sixth = d.execute(call("mika_open_in_browser", """{"url":"https://example.com/6"}""")).content
        assertEquals("rate_limited", sixth["error"]!!.jsonPrimitive.content)
    }

    @Test fun searchAsksThenSearches() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        val out = d.execute(call("mika_search_in_browser", """{"query":"咖啡豆"}""")).content
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("咖啡豆"), port.searches)
    }

    @Test fun sitesAndUserPageAreReadOnly() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        val sites = d.executeRead(call("mika_sites")).content.toString()
        assertTrue(sites, sites.contains("example.com") && sites.contains("\"loggedIn\":true"))
        val page = d.executeRead(call("mika_user_page")).content.toString()
        assertTrue(page, page.contains("old.example.com"))
    }

    @Test fun favoritesCanBeListedAndAdded() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        val added = d.execute(call("mika_favorites", """{"op":"add","host":"example.com"}""")).content
        assertEquals("true", added["ok"]!!.jsonPrimitive.content)
        assertEquals("add", port.favoriteOp)
        assertEquals("example.com", port.favoriteHost)
        val listed = d.execute(call("mika_favorites", """{"op":"list"}""")).content.toString()
        assertTrue(listed, listed.contains("example.com"))
    }

    @Test fun loginAsksThenOpensTheLoginPage() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, "Open")
        val out = d.execute(call("mika_login_site", """{"host":"example.com"}""")).content
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals("example.com", port.login)
    }

    @Test fun confirmLoginDoesNotAsk() = runTest {
        val port = FakeSelf()
        val d = browserDispatcher(port, null)
        val out = d.execute(call("mika_confirm_login")).content
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertTrue(port.confirmed)
    }
}
