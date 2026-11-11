package com.wangzan101.mika.voice

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自建 Fun-ASR 实时服务(Fun-ASR 仓库里的 `serve_realtime_ws.py`)的协议:
 * 文字 `START` 开始一次识别(回 `{"event":"started"}`),之后送 16 kHz 单声道 PCM16 二进制帧;
 * 服务端约每半秒回一份到此刻的全文 `{"sentences":[{"text",...}],"partial":"...","is_final":false}`,
 * 每份都是整份替换(`sentences` 是定下来的句子,`partial` 是还在变的那句);
 * 文字 `STOP` 收尾:先回 `is_final` 为 true 的终结果,再回 `{"event":"stopped"}`。
 * 指定识别语言时先发 `LANGUAGE:<中文语言名>`(服务端回 `language_set`),再发 `START`,确认语言的回应排在「开始」前面。
 * 一段话说得长(中间没有停顿、VAD 还没切句)时,服务端只拿最近 15 秒(`--partial-window-sec`)去解 `partial`,
 * 前面的字就从 `partial` 里掉出去了,要等这段说完才整段放进 `sentences`。所以窗口往后挪时
 * (`partial_start_ms` 变大、`sentences` 没变),这里把上一份 `partial` 里掉出去的那截留下([carried]),
 * 当作定下的字接在句子后面;等服务端把这段放进 `sentences`(或给终结果)就丢掉它。终结果只用服务端的。
 * 每次按住说话用一个新连接,不复用。
 */
internal class FunAsrProtocol(private val language: AsrLanguage = AsrLanguage.Auto) : LiveAsrProtocol {
    private var final: String? = null
    private var lockedCount = 0
    /** 当前这段里已经掉出解码窗口的字。 */
    private var carried = ""
    private var lastPartial = ""
    private var lastStart = 0L
    private var lastDuration = 0L

    override fun opening() = listOfNotNull(language.takeIf { it != AsrLanguage.Auto }?.let { "LANGUAGE:${it.funAsrName}" }, "START")
    override fun closing() = listOf("STOP")

    override fun read(message: String): LiveAsrUpdate {
        val root = Json.parseToJsonElement(message).jsonObject
        when (root["event"]?.jsonPrimitive?.contentOrNull) {
            "started" -> return LiveAsrUpdate.Started
            // 没送过音频时服务端不给终结果、直接回 stopped:当没听清
            "stopped" -> return LiveAsrUpdate.Ended(final ?: "")
            null -> Unit
            else -> return LiveAsrUpdate.None // hotwords_set、language_set 等确认
        }
        val sentences = (root["sentences"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull?.trim() }
        val stable = joinAsr(sentences)
        if (root["is_final"]?.jsonPrimitive?.booleanOrNull == true) {
            final = stable
            return LiveAsrUpdate.Text(stable, "")
        }
        val partial = root["partial"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val start = root["partial_start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: lastStart
        val duration = root["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: lastDuration
        if (sentences.size != lockedCount) {
            // 这段已经整段放进 sentences:掉出去的字由它带着;下一份 partial 是新的一段,不和这段对齐
            lockedCount = sentences.size
            carried = ""
            lastPartial = ""
        } else if (partial.isNotEmpty() && lastPartial.isNotEmpty() && start > lastStart) {
            carried += droppedPrefix(lastPartial, partial, lastStart, lastDuration, start)
        }
        if (partial.isNotEmpty()) { lastPartial = partial; lastStart = start; lastDuration = duration }
        return LiveAsrUpdate.Text(joinAsr(listOf(stable, carried)), partial)
    }

    companion object {
        /**
         * 解码窗口往后挪了:上一份 [previous](从 [previousStart] 到 [previousEnd] 毫秒)里,哪一截在新的 [next](从 [nextStart] 起)里没有了。
         * 先拿 [next] 开头几个字(不算标点)在 [previous] 里找,找到的位置之前就是掉出去的;找不到时按时间比例估。
         */
        internal fun droppedPrefix(previous: String, next: String, previousStart: Long, previousEnd: Long, nextStart: Long): String {
            val words = previous.withIndex().filter { it.value.isLetterOrDigit() }
            val key = next.filter(Char::isLetterOrDigit).take(KEY_CHARS)
            if (key.length >= 2) {
                val at = words.joinToString("") { it.value.toString() }.indexOf(key)
                if (at >= 0) return previous.substring(0, words[at].index)
            }
            val span = previousEnd - previousStart
            if (span <= 0) return ""
            val cut = (previous.length * ((nextStart - previousStart).toDouble() / span)).toInt().coerceIn(0, previous.length)
            return previous.substring(0, cut)
        }

        /** 拿新窗口开头几个字去对齐。 */
        private const val KEY_CHARS = 4

        /** 服务端默认端口。 */
        const val DEFAULT_PORT = 10095

        /** 卡片、诊断里只写主机和端口,不写路径和参数。 */
        fun hostOf(url: String): String = runCatching { URI(url).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrDefault(url)

        /**
         * 用户填的服务地址规范成 ws(s) 地址:没写协议当 ws,http(s) 换成 ws(s),没写端口补 [DEFAULT_PORT];
         * 不是合法地址(没有主机、协议不对、带账号密码)返回 null。
         */
        fun normalize(input: String): String? {
            val raw = input.trim()
            if (raw.isEmpty() || raw.any(Char::isWhitespace)) return null
            val withScheme = if ("://" in raw) raw else "ws://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
            val host = uri.host ?: return null
            if (uri.userInfo != null) return null
            val port = if (uri.port > 0) uri.port else if (scheme == "ws" && "://" !in raw) DEFAULT_PORT else -1
            val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$hostPart${if (port > 0) ":$port" else ""}$path$query"
        }
    }
}
