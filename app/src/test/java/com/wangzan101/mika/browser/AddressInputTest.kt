package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressInputTest {
    private fun bing(q: String) = "https://www.bing.com/search?q=$q"

    @Test
    fun blankIsNothing() {
        assertNull(AddressInput.resolve(""))
        assertNull(AddressInput.resolve("   "))
    }

    @Test
    fun addressesWithSchemeOpenAsTyped() {
        assertEquals("https://example.com/a?b=1", AddressInput.resolve("  https://example.com/a?b=1 "))
        assertEquals("http://example.com", AddressInput.resolve("http://example.com"))
        assertEquals("HTTPS://Example.com", AddressInput.resolve("HTTPS://Example.com"))
        // 别的协议也原样交出去,由网址规则拦下
        assertEquals("ftp://example.com", AddressInput.resolve("ftp://example.com"))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://example.com", AddressInput.resolve("example.com"))
        assertEquals("https://www.example.co.uk/path?q=1#top", AddressInput.resolve("www.example.co.uk/path?q=1#top"))
        assertEquals("https://sub-1.example.org:8443/x", AddressInput.resolve("sub-1.example.org:8443/x"))
        assertEquals("https://xn--fsq.xn--0zwm56d", AddressInput.resolve("xn--fsq.xn--0zwm56d"))
    }

    @Test
    fun localhostAndIpsGetHttp() {
        assertEquals("http://localhost", AddressInput.resolve("localhost"))
        assertEquals("http://localhost:8080/app", AddressInput.resolve("localhost:8080/app"))
        assertEquals("http://192.168.1.10", AddressInput.resolve("192.168.1.10"))
        assertEquals("http://10.0.0.1:3000/", AddressInput.resolve("10.0.0.1:3000/"))
        assertEquals("http://[::1]:8080/", AddressInput.resolve("[::1]:8080/"))
    }

    @Test
    fun everythingElseIsSearched() {
        assertEquals(bing("hello+world"), AddressInput.resolve("hello world"))
        assertEquals(bing("example.com+login"), AddressInput.resolve("example.com login"))
        assertEquals(bing("kotlin"), AddressInput.resolve("kotlin"))
        assertEquals(bing("%E5%A4%A9%E6%B0%94"), AddressInput.resolve("天气"))
        assertEquals(bing("%E4%B8%AD%E6%96%87.%E7%BD%91%E7%AB%99"), AddressInput.resolve("中文.网站"))
        assertEquals(bing("user%40example.com"), AddressInput.resolve("user@example.com"))
        // 像版本号、小数、越界的 IP、不完整的域名、越界的端口
        assertEquals(bing("1.5"), AddressInput.resolve("1.5"))
        assertEquals(bing("999.1.1.1"), AddressInput.resolve("999.1.1.1"))
        assertEquals(bing("example.c"), AddressInput.resolve("example.c"))
        assertEquals(bing("localhost%3A99999"), AddressInput.resolve("localhost:99999"))
        assertEquals(bing("a%26b%3Dc"), AddressInput.resolve("a&b=c"))
    }

    @Test
    fun eachEngineSearchesAndReadsBack() {
        for (e in SearchEngine.entries) {
            val url = e.searchUrl("天气 预报")
            assertEquals("天气 预报", e.queryOf(url))
            assertEquals(url, AddressInput.search("天气 预报", e))
            assertEquals(url, AddressInput.resolve("天气 预报", e))
            assertEquals("天气 预报", AddressInput.searchQuery(url))
            assertEquals(e, SearchEngineSpec.of(url, SearchEngine.entries))
        }
    }

    @Test
    fun customEngineSearchesAndReadsBack() {
        val custom = CustomSearchEngine.create("Example", "https://search.example.com/find?q=%s")!!
        val url = custom.searchUrl("天气 预报")
        assertEquals("https://search.example.com/find?q=%E5%A4%A9%E6%B0%94+%E9%A2%84%E6%8A%A5", url)
        assertEquals("天气 预报", custom.queryOf(url))
        assertEquals(url, AddressInput.resolve("天气 预报", custom))
        val engines = SearchEngine.entries + custom
        assertEquals(custom, SearchEngineSpec.of(url, engines))
        assertEquals("天气 预报", AddressInput.searchQuery(url, engines))
        assertEquals(null, AddressInput.searchQuery(url))
    }

    @Test
    fun customEngineWithSuffixReadsBackWords() {
        val custom = CustomSearchEngine.create("Example", "https://search.example.com/?q=%s&lang=zh")!!
        assertEquals("天气", custom.queryOf(custom.searchUrl("天气")))
        // 网站后面又加了参数也认得
        assertEquals("天气", custom.queryOf(custom.searchUrl("天气") + "&source=nav"))
    }

    @Test
    fun customEngineSuffixInsideTheQueryIsNotMistakenForTheEnd() {
        val custom = CustomSearchEngine.create("Example", "https://search.example.com/?q=%s_zh-cn")!!
        assertEquals("weather_zh-cn", custom.queryOf(custom.searchUrl("weather_zh-cn")))
    }

    @Test
    fun googleAndBaiduUseTheirOwnUrls() {
        assertEquals("https://www.google.com/search?q=hello+world", AddressInput.resolve("hello world", SearchEngine.Google))
        assertEquals("https://www.baidu.com/s?wd=hello+world", AddressInput.resolve("hello world", SearchEngine.Baidu))
        assertEquals("https://duckduckgo.com/?q=hello+world", AddressInput.resolve("hello world", SearchEngine.DuckDuckGo))
    }

    @Test
    fun searchQueryReadsBackTheWords() {
        assertEquals("天气 预报", AddressInput.searchQuery(AddressInput.search("天气 预报")))
        assertEquals("a&b", AddressInput.searchQuery(AddressInput.search("a&b") + "&form=x"))
        assertEquals(null, AddressInput.searchQuery("https://www.example.com/a/b"))
        assertEquals(null, AddressInput.searchQuery(SearchEngine.Bing.searchUrl("")))
        assertEquals(null, AddressInput.searchQuery(SearchEngine.Google.searchUrl("   ")))
    }
}
