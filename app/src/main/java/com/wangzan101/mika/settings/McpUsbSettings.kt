package com.wangzan101.mika.settings

import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.remote.RemoteOwnership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * 经 USB 的电脑连接(电脑用 adb 打开的那种)的开关和令牌。和局域网那种([McpSettings])各管各的,令牌也各用各的。
 *
 * - 打开时生成一个新令牌;已经开着再打开沿用原来的(电脑重跑一次命令,已经配好的不失效)。
 * - 监听起来后 [publish] 把连接信息写到 [infoFile](外部文件目录,安卓 11 起只有 Mika 和 shell 读得到)。
 * - 关掉时忘掉令牌(下次打开换新的)、删掉连接信息文件。
 * - 不跨重启:开关记着是哪次开机([bootId])打开的,换了一次开机就当关着,顺手清掉上次留下的文件。
 *
 * 令牌同时以明文写在连接信息文件里(电脑要读),所以 App 自己的私有目录里也存明文,不另加密。
 */
class McpUsbSettings(
    private val dir: File,
    private val bootId: () -> String,
    private val infoFile: () -> File?,
    private val random: SecureRandom = SecureRandom(),
    migrateTaskOwner: (String) -> Unit = {},
) {
    private val file = File(dir, "mcp-usb.json")
    private val _enabled = MutableStateFlow(false)
    @Volatile private var token: String? = null

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val boot = (o?.get("boot") as? JsonPrimitive)?.content
        val t = (o?.get("token") as? JsonPrimitive)?.content?.takeIf { it.length >= MIN_TOKEN }
        // 旧版用令牌摘要作为归属，在清除上次开机的令牌前先迁移它的任务。
        if (t != null) migrateTaskOwner(RemoteOwnership.of(t))
        if (boot != null && boot == bootId() && t != null) {
            token = t
            _enabled.value = true
        } else {
            file.delete()
            runCatching { infoFile()?.delete() }
        }
    }

    /** 开着时的令牌;关着为 null。 */
    fun token(): String? = token

    /** 打开;已经开着就沿用原来的令牌。返回此刻的令牌。 */
    @Synchronized fun enable(): String {
        token?.let { return it }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("boot", bootId()); put("token", t) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        token = t
        _enabled.value = true
        return t
    }

    /** 关掉:令牌当场作废,连接信息文件删掉。 */
    @Synchronized fun disable() {
        token = null
        _enabled.value = false
        file.delete()
        unpublish()
    }

    /** 监听起来了:把连接信息写给电脑(先写临时文件再改名,电脑正在读时不会读到半截)。关着时什么也不做。 */
    @Synchronized fun publish(port: Int) {
        val t = token ?: return
        val f = infoFile() ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(McpUsb.info(port, t, bootId()))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** 没在监听(没开起来、关了):删掉连接信息文件,电脑那边的脚本就知道没开成。 */
    @Synchronized fun unpublish() {
        runCatching { infoFile()?.delete() }
    }

    private companion object {
        const val MIN_TOKEN = 32
    }
}
