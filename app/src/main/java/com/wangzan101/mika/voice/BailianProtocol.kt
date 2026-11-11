package com.wangzan101.mika.voice

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 阿里云百炼实时语音识别的 WebSocket 协议(fun-asr-realtime、paraformer-realtime-v2 等用同一套 run-task 指令):
 * 连上后发 `run-task`,等 `task-started` 再送 16 kHz 单声道 PCM16 二进制帧;
 * `result-generated` 里的 `sentence` 按 `sentence_id` 是同一句的整份替换,`sentence_end` 为真时这句定下;
 * 送完音频发 `finish-task`,等 `task-finished` 才算结束(不等就会丢掉最后一句);`task-failed` 是出错,连接随之关闭。
 */
internal class BailianProtocol(private val model: String, private val language: AsrLanguage = AsrLanguage.Auto) : LiveAsrProtocol {
    private val taskId = UUID.randomUUID().toString().replace("-", "")
    private val sentences = sortedMapOf<Int, String>()
    private val ended = mutableSetOf<Int>()

    // 官方示例 100 ms 一包、100 ms 一发;连上前攒下的音频按两倍速追上
    override val packetGapMs = 50L

    override fun opening() = listOf(buildJsonObject {
        putJsonObject("header") {
            put("action", "run-task"); put("task_id", taskId); put("streaming", "duplex")
        }
        putJsonObject("payload") {
            put("task_group", "audio"); put("task", "asr"); put("function", "recognition"); put("model", model)
            putJsonObject("parameters") {
                put("format", "pcm"); put("sample_rate", 16000)
                // 指定语言时只给一个(fun-asr-realtime 只认第一个)
                if (language != AsrLanguage.Auto) put("language_hints", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(language.code)) })
                // 通义听写消息版默认不给中间结果,打开才能边说边出字
                if (model.startsWith("qwen-audio")) put("intermediate_result_enabled", true)
                // 按住说话不封顶,中间停下想一想时静音较久:开心跳,服务端不因长时间静音断开(心跳结果在 read 里跳过)
                else put("heartbeat", true)
            }
            putJsonObject("input") {}
        }
    }.toString())

    override fun closing() = listOf(buildJsonObject {
        putJsonObject("header") {
            put("action", "finish-task"); put("task_id", taskId); put("streaming", "duplex")
        }
        putJsonObject("payload") { putJsonObject("input") {} }
    }.toString())

    override fun read(message: String): LiveAsrUpdate {
        val root = Json.parseToJsonElement(message).jsonObject
        val header = root["header"] as? JsonObject ?: return LiveAsrUpdate.None
        return when (header["event"]?.jsonPrimitive?.contentOrNull) {
            "task-started" -> LiveAsrUpdate.Started
            "task-finished" -> LiveAsrUpdate.Ended(joinAsr(sentences.values.toList()))
            "task-failed" -> {
                val code = header["error_code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                // 只留错误码:服务端原话可能带着说的话
                throw LiveAsrError(code, auth = AUTH_CODES.any { code.contains(it, ignoreCase = true) })
            }
            "result-generated" -> sentence(root)
            else -> LiveAsrUpdate.None
        }
    }

    private fun sentence(root: JsonObject): LiveAsrUpdate {
        val s = root["payload"]?.jsonObject?.get("output")?.jsonObject?.get("sentence") as? JsonObject ?: return LiveAsrUpdate.None
        if (s["heartbeat"]?.jsonPrimitive?.booleanOrNull == true) return LiveAsrUpdate.None
        val text = s["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return LiveAsrUpdate.None
        // 没给编号的接在最后一句上
        val id = s["sentence_id"]?.jsonPrimitive?.intOrNull ?: (sentences.keys.lastOrNull() ?: 1)
        if (id in ended && s["sentence_end"]?.jsonPrimitive?.booleanOrNull != true) return LiveAsrUpdate.None
        sentences[id] = text
        if (s["sentence_end"]?.jsonPrimitive?.booleanOrNull == true) ended += id
        val open = sentences.keys.filter { it !in ended }
        return LiveAsrUpdate.Text(
            joinAsr(sentences.filterKeys { it in ended }.values.toList()),
            joinAsr(open.map { sentences.getValue(it) }),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "fun-asr-realtime"

        /** 设置页里列出的几个;也可以手填别的同协议模型。 */
        val MODELS = listOf("fun-asr-realtime", "paraformer-realtime-v2", "qwen-audio-3.1-asr-flash-message")

        private val AUTH_CODES = listOf("ApiKey", "Access", "Arrearage", "Unauthorized", "Forbidden", "Permission")

        /**
         * 业务空间 ID 规范成主机名里的那一段:去掉两头空白、转小写,没带 `llm-` 前缀时补上(控制台里的空间 ID 以它开头,
         * 只抄了后半段也能用);空串表示不用业务空间。只能是字母、数字和连字符,否则返回 null。
         */
        fun workspace(input: String): String? {
            val raw = input.trim().lowercase()
            if (raw.isEmpty()) return ""
            if (!raw.matches(Regex("[a-z0-9-]{1,63}")) || raw.startsWith("-") || raw.endsWith("-")) return null
            val id = if (raw.startsWith("llm-")) raw else "llm-$raw"
            return id.takeIf { it.length <= 63 && it != "llm-" }
        }

        /** 模型名拼进 JSON,只收常见字符。 */
        fun validModel(model: String): Boolean = model.trim().matches(Regex("[A-Za-z0-9._-]{1,80}"))
    }
}

/**
 * 百炼的地域:北京、新加坡的 API Key 不通用,要和接入点同一地域。存的是名字,只能往后加。
 * [endpoint] 是通用接入点(没填业务空间 ID 时用);[maas] 是业务空间专属接入点里的地域段。
 */
enum class BailianRegion(val endpoint: String, val maas: String) {
    Beijing("wss://dashscope.aliyuncs.com/api-ws/v1/inference", "cn-beijing"),
    Singapore("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", "ap-southeast-1");

    /**
     * 这次连哪里:填了业务空间 ID 用它的专属接入点 `wss://<空间>.<地域>.maas.aliyuncs.com/api-ws/v1/inference`(文档推荐),
     * 没填用通用接入点(仍可用)。[workspace] 先经 [BailianProtocol.workspace] 规范。
     */
    fun endpoint(workspace: String): String =
        if (workspace.isEmpty()) endpoint else "wss://$workspace.$maas.maas.aliyuncs.com/api-ws/v1/inference"
}
