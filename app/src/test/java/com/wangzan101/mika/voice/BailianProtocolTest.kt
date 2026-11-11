package com.wangzan101.mika.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 百炼实时语音识别的 run-task 协议;消息样例照官方文档的格式。 */
class BailianProtocolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun result(id: Int, text: String, end: Boolean, heartbeat: Boolean = false) =
        """{"header":{"task_id":"t","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"begin_time":170,"end_time":${if (end) 920 else "null"},"text":"$text","heartbeat":$heartbeat,"sentence_end":$end,"sentence_id":$id,"words":[]}},"usage":null}}"""

    @Test fun runTaskAndFinishTaskShareOneTaskId() {
        val p = BailianProtocol("fun-asr-realtime")
        val run = obj(p.opening().single())
        val header = run.getValue("header").jsonObject
        assertEquals("run-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("duplex", header.getValue("streaming").jsonPrimitive.content)
        val taskId = header.getValue("task_id").jsonPrimitive.content
        assertTrue(taskId.matches(Regex("[0-9a-f]{32}")))
        val payload = run.getValue("payload").jsonObject
        assertEquals("audio", payload.getValue("task_group").jsonPrimitive.content)
        assertEquals("asr", payload.getValue("task").jsonPrimitive.content)
        assertEquals("recognition", payload.getValue("function").jsonPrimitive.content)
        assertEquals("fun-asr-realtime", payload.getValue("model").jsonPrimitive.content)
        val params = payload.getValue("parameters").jsonObject
        assertEquals("pcm", params.getValue("format").jsonPrimitive.content)
        assertEquals("16000", params.getValue("sample_rate").jsonPrimitive.content)
        assertNull(params["intermediate_result_enabled"])
        assertEquals("true", params.getValue("heartbeat").jsonPrimitive.content)
        val finish = obj(p.closing().single()).getValue("header").jsonObject
        assertEquals("finish-task", finish.getValue("action").jsonPrimitive.content)
        assertEquals(taskId, finish.getValue("task_id").jsonPrimitive.content)
        // 每次会话一个新任务
        assertFalse(BailianProtocol("fun-asr-realtime").opening().single().contains(taskId))
    }

    @Test fun languageHintIsOneCodeOnlyWhenChosen() {
        fun params(l: AsrLanguage) = obj(BailianProtocol("fun-asr-realtime", l).opening().single())
            .getValue("payload").jsonObject.getValue("parameters").jsonObject
        assertEquals("[\"ja\"]", params(AsrLanguage.Japanese).getValue("language_hints").toString())
        assertEquals("[\"yue\"]", params(AsrLanguage.Cantonese).getValue("language_hints").toString())
        assertNull(params(AsrLanguage.Auto)["language_hints"])
    }

    @Test fun languageCodesReadBackAndUnknownIsAuto() {
        for (l in AsrLanguage.entries) assertEquals(l, AsrLanguage.of(l.code))
        assertEquals(AsrLanguage.Auto, AsrLanguage.of(null))
        assertEquals(AsrLanguage.Auto, AsrLanguage.of("xx"))
    }

    @Test fun messageModelsAskForIntermediateResults() {
        val params = obj(BailianProtocol("qwen-audio-3.1-asr-flash-message").opening().single())
            .getValue("payload").jsonObject.getValue("parameters").jsonObject
        assertEquals("true", params.getValue("intermediate_result_enabled").jsonPrimitive.content)
        assertNull(params["heartbeat"])
    }

    @Test fun sentencesReplaceByIdAndAreKeptWhenTheyEnd() {
        val p = BailianProtocol("fun-asr-realtime")
        assertEquals(LiveAsrUpdate.Started, p.read("""{"header":{"task_id":"t","event":"task-started","attributes":{}},"payload":{}}"""))
        assertEquals(LiveAsrUpdate.Text("", "好"), p.read(result(1, "好", end = false)))
        assertEquals(LiveAsrUpdate.Text("", "好，我知道"), p.read(result(1, "好，我知道", end = false)))
        assertEquals(LiveAsrUpdate.Text("好，我知道了。", ""), p.read(result(1, "好，我知道了。", end = true)))
        // 心跳不改文字
        assertEquals(LiveAsrUpdate.None, p.read(result(0, "", end = false, heartbeat = true)))
        assertEquals(LiveAsrUpdate.Text("好，我知道了。", "明天"), p.read(result(2, "明天", end = false)))
        assertEquals(LiveAsrUpdate.Text("好，我知道了。明天见。", ""), p.read(result(2, "明天见。", end = true)))
        // 定下的句子不被迟到的中间结果改回去
        assertEquals(LiveAsrUpdate.None, p.read(result(1, "好", end = false)))
        assertEquals(
            LiveAsrUpdate.Ended("好，我知道了。明天见。"),
            p.read("""{"header":{"task_id":"t","event":"task-finished","attributes":{}},"payload":{"output":{},"usage":null}}"""),
        )
    }

    @Test fun finishWithoutSentencesIsEmpty() {
        assertEquals(LiveAsrUpdate.Ended(""), BailianProtocol("fun-asr-realtime").read("""{"header":{"event":"task-finished"},"payload":{}}"""))
    }

    @Test fun failuresKeepOnlyTheCode() {
        val p = BailianProtocol("fun-asr-realtime")
        val auth = assertThrows(LiveAsrError::class.java) {
            p.read("""{"header":{"task_id":"t","event":"task-failed","error_code":"InvalidApiKey","error_message":"secret user words","attributes":{}},"payload":{}}""")
        }
        assertTrue(auth.auth)
        assertEquals("InvalidApiKey", auth.code)
        assertFalse(auth.message!!.contains("secret"))
        val other = assertThrows(LiveAsrError::class.java) {
            p.read("""{"header":{"task_id":"t","event":"task-failed","error_code":"CLIENT_ERROR","error_message":"request timeout after 23 seconds.","attributes":{}},"payload":{}}""")
        }
        assertFalse(other.auth)
        assertEquals("CLIENT_ERROR", other.code)
    }

    @Test fun unknownEventsAreIgnored() {
        assertEquals(LiveAsrUpdate.None, BailianProtocol("m").read("""{"header":{"event":"something-new"},"payload":{}}"""))
        assertEquals(LiveAsrUpdate.None, BailianProtocol("m").read("""{"payload":{}}"""))
    }

    @Test fun modelNamesAndEndpoints() {
        assertTrue(BailianProtocol.validModel("fun-asr-realtime-2026-02-28"))
        assertTrue(BailianProtocol.validModel("qwen-audio-3.1-asr-flash-message"))
        assertFalse(BailianProtocol.validModel(""))
        assertFalse(BailianProtocol.validModel("bad\"model"))
        assertFalse(BailianProtocol.validModel("two words"))
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", BailianRegion.Beijing.endpoint(""))
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", BailianRegion.Singapore.endpoint(""))
        assertEquals("wss://llm-abc123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", BailianRegion.Beijing.endpoint("llm-abc123"))
        assertEquals("wss://llm-abc123.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/inference", BailianRegion.Singapore.endpoint("llm-abc123"))
    }

    // 业务空间 ID 拼进主机名:没带 llm- 前缀时补上,只收字母、数字和连字符
    @Test fun workspaceIdsAreNormalized() {
        assertEquals("", BailianProtocol.workspace("  "))
        assertEquals("llm-abc123", BailianProtocol.workspace(" LLM-abc123 "))
        assertEquals("llm-abc123", BailianProtocol.workspace("abc123"))
        for (bad in listOf("a.b", "a/b", "evil.com#", "-abc", "abc-", "llm-", "a b", "x".repeat(64))) assertNull(bad, BailianProtocol.workspace(bad))
    }
}
