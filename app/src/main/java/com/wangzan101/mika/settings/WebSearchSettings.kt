package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.search.HttpWebSearch
import com.wangzan101.mika.core.search.WebSearch
import java.util.Base64

/** 通用搜索设置。默认关闭且没有端点;只有用户明确配置并启用后才创建服务。 */
data class WebSearchConfig(val enabled: Boolean = false, val endpoint: String = "", val apiKey: String = "")

class WebSearchSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-web-search-key"),
) {
    fun get(): WebSearchConfig {
        val key = prefs.getString(API_KEY, null)?.let { encoded ->
            runCatching { String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8) }.getOrDefault("")
        }.orEmpty()
        return WebSearchConfig(prefs.getBoolean(ENABLED, false), prefs.getString(ENDPOINT, null).orEmpty(), key)
    }

    @Synchronized
    fun set(config: WebSearchConfig) {
        val endpoint = config.endpoint.trim()
        require(HttpWebSearch.parseEndpoint(endpoint) != null || (endpoint.isEmpty() && !config.enabled)) { "Invalid web search endpoint" }
        val edit = prefs.edit().putBoolean(ENABLED, config.enabled).putString(ENDPOINT, endpoint)
        val key = config.apiKey.trim()
        if (key.isEmpty()) edit.remove(API_KEY)
        else edit.putString(API_KEY, Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
        check(edit.commit()) { "Cannot save web search settings" }
    }

    /** 每次任务重读设置;关闭、没配端点或配置损坏时返回 null,任务只会走浏览器。 */
    fun service(): WebSearch? {
        val config = get()
        if (!config.enabled) return null
        val endpoint = HttpWebSearch.parseEndpoint(config.endpoint) ?: return null
        return HttpWebSearch(endpoint.toString(), config.apiKey)
    }

    companion object {
        private const val PREFS = "web_search"
        private const val ENABLED = "enabled"
        private const val ENDPOINT = "endpoint"
        private const val API_KEY = "api_key_encrypted"

        fun get(context: Context) = WebSearchSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
