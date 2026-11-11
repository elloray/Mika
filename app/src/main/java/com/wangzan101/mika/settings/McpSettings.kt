package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * 电脑连接(MCP)的设置:开关和端口存成一个小 JSON 文件(读不懂按关着);
 * 访问令牌和服务商的 Key 一样经 [KeyCipher] 加密后另存一个文件,第一次要用时生成。
 * 默认关着。
 */
class McpSettings(private val dir: File, private val cipher: KeyCipher, private val random: SecureRandom = SecureRandom()) {
    private val file = File(dir, "mcp.json")
    private val tokenFile = File(dir, "mcp-token.bin")
    private val _enabled: MutableStateFlow<Boolean>
    private val _historyEnabled: MutableStateFlow<Boolean>
    @Volatile private var cachedToken: String? = null

    /** 监听的端口;文件里没写就用 [DEFAULT_PORT]。 */
    val port: Int get() = portValue

    @Volatile private var portValue: Int

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _enabled = MutableStateFlow((o?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false)
        _historyEnabled = MutableStateFlow((o?.get("historyEnabled") as? JsonPrimitive)?.booleanOrNull ?: false)
        portValue = (o?.get("port") as? JsonPrimitive)?.intOrNull?.takeIf { it in PORT_RANGE } ?: DEFAULT_PORT
    }

    /** 换一个监听端口(电脑连接开着时由调用方重绑并重写连接信息);[port] 必须在 [PORT_RANGE] 里。 */
    @Synchronized fun setPort(port: Int) {
        require(port in PORT_RANGE) { "port must be in $PORT_RANGE" }
        if (port == portValue) return
        portValue = port
        save()
    }

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val historyEnabled: StateFlow<Boolean> = _historyEnabled.asStateFlow()

    /** Applies to authenticated USB and private-network clients; older settings grant no history access. */
    @Synchronized fun setHistoryEnabled(on: Boolean) {
        _historyEnabled.value = on
        save()
    }

    @Synchronized fun setEnabled(on: Boolean) {
        _enabled.value = on
        save()
    }

    /** 当前的访问令牌;还没有(或读不出来)就生成一个存下。 */
    @Synchronized fun token(): String {
        cachedToken?.let { return it }
        val stored = runCatching { if (tokenFile.isFile) String(cipher.decrypt(tokenFile.readBytes()), Charsets.UTF_8) else null }.getOrNull()
        return stored?.takeIf { it.length >= MIN_TOKEN }?.also { cachedToken = it } ?: regenerate()
    }

    /** 换一个新令牌:旧的当场失效,连着的电脑要换上新的才能再用。 */
    @Synchronized fun regenerate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        dir.mkdirs()
        val tmp = File(dir, tokenFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cachedToken = token
        return token
    }

    @Synchronized private fun save() {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject {
            put("enabled", _enabled.value); put("port", port); put("historyEnabled", _historyEnabled.value)
        }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        /** 端口允许的范围;读取和写入同一套。 */
        val PORT_RANGE = 1024..65535
        private const val MIN_TOKEN = 32
    }
}
