package com.wangzan101.mika.cap.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress

/**
 * 内置浏览器(本进程所有 WebView)的网络代理,跟设置 › 网络代理 走同一个地址。
 * 安卓的 WebView 只有支持「代理覆盖」(androidx.webkit 的 [ProxyController])时才能单独给 App 设代理;
 * 不支持的、或代理是带账号密码的 SOCKS(浏览器内核不支持 SOCKS 认证)时,浏览器直连,设置页写明。
 * 本机、局域网地址照旧直连;HTTP 代理要账号密码时由 [credentialsFor] 回答(见 `WebTab`)。
 */
object WebProxy {
    /** 浏览器这边此刻的情况。 */
    enum class Mode {
        /** 没设代理(或代理只给容器用):直连。 */
        Off,
        /** 浏览器走代理。 */
        Applied,
        /** 这台手机的 WebView 不支持单独设代理:直连。 */
        Unsupported,
        /** 带账号密码的 SOCKS 代理浏览器用不了:直连。 */
        SocksWithCredentials,
    }

    private const val TAG = "MikaWebProxy"
    @Volatile private var overridden = false

    /** 按此刻的设置决定浏览器怎么连(纯函数,便于测试)。 */
    fun mode(address: ProxyAddress?, supported: Boolean): Mode = when {
        address == null -> Mode.Off
        !supported -> Mode.Unsupported
        address.socks && address.hasCredentials -> Mode.SocksWithCredentials
        else -> Mode.Applied
    }

    /** 浏览器内核认的代理规则写法。SOCKS 有的内核只认 `socks://`,先试 `socks5://`。 */
    fun rules(address: ProxyAddress): List<String> {
        val host = if (address.host.contains(':')) "[${address.host}]" else address.host
        return if (address.socks) listOf("socks5://$host:${address.port}", "socks://$host:${address.port}")
        else listOf("http://$host:${address.port}")
    }

    /** WebView 支不支持单独设代理;查询本身会加载 WebView,放在后台线程调。 */
    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** 此刻浏览器这边的情况(会加载 WebView,放在后台线程调)。 */
    fun mode(): Mode = NetProxy.shared.current().let { mode(it, it != null && supported()) }

    /**
     * 按此刻的设置给本进程的 WebView 设上(或撤掉)代理。没设代理、也没设过时什么都不做(不为此加载 WebView)。
     * 在主线程上做;可以从任何线程调。
     */
    fun apply(context: Context) {
        val address = NetProxy.shared.current()
        if (address == null && !overridden) return
        Handler(Looper.getMainLooper()).post {
            runCatching { applyNow(address) }.onFailure { Log.w(TAG, "proxy override failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun applyNow(address: ProxyAddress?) {
        if (!supported()) return
        val controller = ProxyController.getInstance()
        if (mode(address, true) != Mode.Applied || address == null) {
            if (overridden) controller.clearProxyOverride({ it.run() }, {})
            overridden = false
            return
        }
        for (rule in rules(address)) {
            val config = ProxyConfig.Builder().addProxyRule(rule).bypassSimpleHostnames().apply {
                LocalHosts.BYPASS_RULES.forEach { addBypassRule(it) }
            }.build()
            val ok = runCatching { controller.setProxyOverride(config, { it.run() }, {}) }.isSuccess
            if (ok) {
                overridden = true
                return
            }
        }
    }

    /** 浏览器问账号密码时:问的是当前 HTTP 代理才回答,别的主机(网站自己的登录框)不答。 */
    fun credentialsFor(host: String?): Pair<String, String>? {
        val p = NetProxy.shared.current() ?: return null
        if (p.socks || !p.hasCredentials || host == null) return null
        if (!host.removeSurrounding("[", "]").equals(p.host, ignoreCase = true)) return null
        return p.user to p.password
    }
}

/** 每个标签的主文档最多替代理答三次;下一次导航重新计数,改对设置后不必关掉标签。只在主线程使用。 */
internal class WebProxyAuth {
    private var answers = 0

    fun onPageStarted() { answers = 0 }

    fun credentialsFor(host: String?): Pair<String, String>? {
        val credentials = WebProxy.credentialsFor(host) ?: return null
        if (answers >= 3) return null
        answers++
        return credentials
    }
}
