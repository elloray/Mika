package com.wangzan101.mika.core.self

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 模型看和操作 Mika 自己的唯一入口(App 实现,核心只认这些数据)。
 *
 * 手机那一套工具不能碰 Mika 自己的包(见 [com.wangzan101.mika.core.action.ConfirmDecision] 的 ownApp):
 * 不自己给自己点同意、不自己改设置。这个端口是正门,只做几件明确的事:
 * 读自身状态、打开自己的页面/文件、发通知、写剪贴板。每件都在用户开关和当次同意之下。
 */
interface SelfPort {
    /** 此刻的自身状态。只含组件与计数,不含 Key/Token/其他对话内容/浏览历史。 */
    suspend fun status(): SelfStatus

    /** 打开 Mika 自己的一页。前台进导航栈,后台发通知。 */
    suspend fun openScreen(screen: SelfScreen, note: String? = null): SelfResult

    /** 用 App 内查看器打开本任务工作区里的文件([path] 形如 /workspace/…)。 */
    suspend fun openFile(path: String, name: String? = null): SelfResult

    /** 给用户发一条通知;[screen] 不为 null 时带一个「查看」动作。 */
    suspend fun notify(title: String, body: String, screen: SelfScreen? = null): SelfResult

    /** 把文字写进剪贴板;只写不读。 */
    suspend fun copy(text: String): Boolean

    // ---- 用户浏览器(B 阶段) ----

    /** 用户浏览器此刻的样子;只读。 */
    suspend fun browserState(): SelfBrowserState = SelfBrowserState()

    /** 在用户看得见的浏览器里打开一页;[show] 为 false 时只放进后台标签(需要多标签)。 */
    suspend fun openInBrowser(url: String, show: Boolean, reason: String?): SelfResult =
        SelfResult(ok = false, reason = "not_supported")

    /** 用浏览器默认搜索引擎搜索并打开结果页。 */
    suspend fun searchInBrowser(query: String, show: Boolean): SelfResult =
        SelfResult(ok = false, reason = "not_supported")

    /** 站点登记表:登录状态与最近的验证码/风控记录。 */
    suspend fun sites(): List<SelfSite> = emptyList()

    /** 打开「给 Mika 登录」页,让用户自己登录 [host]。 */
    suspend fun loginSite(host: String): SelfResult = SelfResult(ok = false, reason = "not_supported")

    /** 用户说登录好了:核对一次并记下登录状态。 */
    suspend fun confirmLogin(): SelfResult = SelfResult(ok = false, reason = "not_supported")

    /** 常用网站:list / add / remove;[host] 在 add/remove 时必填。返回改动后的列表。 */
    suspend fun favorites(op: String, host: String?): List<SelfFavorite> = emptyList()

    /** 打开链接的同意文案(用 App 当前语言);[query] 不为 null 时是搜索。 */
    fun browserConsent(url: String, query: String?, replaces: Boolean): SelfConsent = SelfConsent("", "", "")

    /** 代登录的同意文案。 */
    fun loginConsent(host: String, replaces: Boolean): SelfConsent = SelfConsent("", "", "")
}

/** 用户浏览器此刻的样子;不含历史。 */
data class SelfBrowserState(
    val pageOpen: Boolean = false,
    val host: String? = null,
    val title: String? = null,
    val url: String? = null,
    /** 这一页正借给任务(本任务或别的任务),不能硬抢。 */
    val loaned: Boolean = false,
    /** 是否支持多标签(单标签时 show=false 不行)。 */
    val multiTab: Boolean = false,
)

/** 站点登记表的一项。 */
data class SelfSite(val host: String, val loggedIn: Boolean, val challenged: Boolean = false)

/** 常用网站的一格。 */
data class SelfFavorite(val host: String, val loggedIn: Boolean)

/** 一次同意的文案与两个选项(已按 App 语言写好)。 */
data class SelfConsent(val prompt: String, val allow: String, val deny: String)

/** Mika 自己的顶层页面;只给这些,不给权限/root/开发者等高级子页。 */
enum class SelfScreen(val wire: String) {
    Chat("chat"),
    Settings("settings"),
    Memory("memory"),
    Plans("plans"),
    Browser("browser"),
    Models("models"),
    Usage("usage"),
    Screens("screens"),
    About("about"),
    ;

    companion object {
        fun of(wire: String?): SelfScreen? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一次自身操作的结果;原因只在失败时出现,给模型看。 */
data class SelfResult(
    val ok: Boolean,
    val opened: Boolean = false,
    /** 前台已把请求交给界面(界面随后处理)。 */
    val requested: Boolean = false,
    /** 后台已发通知,用户点了才进。 */
    val notified: Boolean = false,
    val foreground: Boolean = false,
    val reason: String? = null,
)

fun SelfResult.toJson(): JsonObject = buildJsonObject {
    put("ok", ok)
    if (opened) put("opened", true)
    if (requested) put("requested", true)
    if (notified) put("notified", true)
    put("foreground", foreground)
    reason?.let { put("reason", it) }
}

/**
 * 自身状态。字段都可空:拿不到的不写,不猜。
 * 名称类字段(模型名、MCP server 名、网页标题)来自设置或网站,当数据看,不当指令。
 */
data class SelfStatus(
    val appVersion: String = "",
    val appBuild: String = "",
    val beta: Boolean = false,
    val sdk: Int = 0,
    val selfOperate: Boolean = false,
    val selfConfigure: Boolean = false,
    val foreground: Boolean = false,
    val mainModel: String? = null,
    val accessibility: String = "",
    val builtinShell: String = "",
    val notificationsAllowed: Boolean = false,
    val batteryUnrestricted: Boolean? = null,
    val browserAvailable: Boolean = false,
    val webSearchConfigured: Boolean = false,
    /** 用户浏览器此刻的站点主机名(只在「操作自己」打开时给)。 */
    val userPageHost: String? = null,
    val userPageTitle: String? = null,
    val keptPages: Int = 0,
    /** 本对话正在跑的任务状态;没有为 null。 */
    val taskState: String? = null,
    val taskStep: Int? = null,
    /** 其他对话正在跑的任务数;只给计数,不给内容。 */
    val otherRuns: Int = 0,
    /** 本对话的定时任务数。 */
    val plans: Int = 0,
    /** 有新版时的版本号;没有为 null。 */
    val updateVersion: String? = null,
    val sandbox: String = "",
    val mcpInbound: Boolean = false,
    /** 外部 MCP server 的名字;不写地址、Token。 */
    val mcpServers: List<String> = emptyList(),
    val relayEnabled: Boolean = false,
)

fun SelfStatus.toJson(): JsonObject = buildJsonObject {
    putJsonObject("app") {
        put("version", appVersion)
        put("build", appBuild)
        put("beta", beta)
        put("sdk", sdk)
    }
    putJsonObject("self") {
        put("operate", selfOperate)
        put("configure", selfConfigure)
        put("foreground", foreground)
    }
    putJsonObject("model") {
        mainModel?.let { put("main", it) }
        put("configured", mainModel != null)
    }
    putJsonObject("phone") {
        put("accessibility", accessibility)
        put("builtinShell", builtinShell)
        put("notifications", notificationsAllowed)
        batteryUnrestricted?.let { put("batteryUnrestricted", it) }
    }
    putJsonObject("web") {
        put("browser", browserAvailable)
        put("webSearch", webSearchConfigured)
        userPageHost?.let {
            putJsonObject("userPage") {
                put("host", it)
                put("title", userPageTitle.orEmpty())
            }
        }
        put("keptPages", keptPages)
    }
    putJsonObject("sandbox") { put("status", sandbox) }
    putJsonObject("mcp") {
        put("inbound", mcpInbound)
        putJsonArray("servers") { mcpServers.forEach { add(JsonPrimitive(it)) } }
    }
    putJsonObject("relay") { put("enabled", relayEnabled) }
    putJsonObject("tasks") {
        taskState?.let { put("thisConversation", it) }
        taskStep?.let { put("step", it) }
        put("otherConversations", otherRuns)
    }
    putJsonObject("plans") { put("thisConversation", plans) }
    updateVersion?.let { putJsonObject("update") { put("available", it) } }
}
