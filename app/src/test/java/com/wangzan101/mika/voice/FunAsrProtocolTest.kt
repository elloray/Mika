package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自建 Fun-ASR 实时服务的消息;样例取自真实服务的回应(说的是一句中文)。 */
class FunAsrProtocolTest {
    @Test fun openingAndClosingAreThePlainTextCommands() {
        val p = FunAsrProtocol()
        assertEquals(listOf("START"), p.opening())
        assertEquals(listOf("STOP"), p.closing())
        assertEquals(0L, p.packetGapMs)
    }

    // 指定语言时先发 LANGUAGE(服务端提示词用中文语言名),再发 START;自动时不发
    @Test fun languageIsSentBeforeStart() {
        assertEquals(listOf("LANGUAGE:中文", "START"), FunAsrProtocol(AsrLanguage.Chinese).opening())
        assertEquals(listOf("LANGUAGE:英文", "START"), FunAsrProtocol(AsrLanguage.English).opening())
        assertEquals(listOf("LANGUAGE:粤语", "START"), FunAsrProtocol(AsrLanguage.Cantonese).opening())
        assertEquals(listOf("START"), FunAsrProtocol(AsrLanguage.Auto).opening())
        // 服务端对 LANGUAGE 的确认排在 started 前面,不算开始
        assertEquals(LiveAsrUpdate.None, FunAsrProtocol(AsrLanguage.Chinese).read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun snapshotsReplaceTheWholeTextAndFinalWaitsForStopped() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.Started, p.read("""{"event": "started"}"""))
        assertEquals(LiveAsrUpdate.Text("", ""), p.read("""{"sentences": [], "partial": "", "partial_start_ms": 0, "duration_ms": 500, "is_final": false}"""))
        assertEquals(LiveAsrUpdate.Text("", "你好，帮。"), p.read("""{"sentences": [], "partial": "你好，帮。", "partial_start_ms": 0, "duration_ms": 1000, "is_final": false}"""))
        assertEquals(
            LiveAsrUpdate.Text("先打开微信。", "给张三发消息"),
            p.read("""{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800, "spk": 0}], "partial": "给张三发消息", "partial_start_ms": 1800, "duration_ms": 3000, "is_final": false}"""),
        )
        val final = """{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800}, {"text": "给张三发一条消息。", "start": 1800, "end": 6867}], "partial": "", "partial_start_ms": 0, "duration_ms": 6867, "is_final": true}"""
        assertEquals(LiveAsrUpdate.Text("先打开微信。给张三发一条消息。", ""), p.read(final))
        assertEquals(LiveAsrUpdate.Ended("先打开微信。给张三发一条消息。"), p.read("""{"event": "stopped"}"""))
    }

    // 一段话说得长、还没切句时服务端只解最近 15 秒:窗口往后挪掉出去的字留在预览里,不被删掉(样例照真实服务的回应)
    @Test fun textThatSlidesOutOfThePartialWindowIsKept() {
        val p = FunAsrProtocol()
        fun partial(text: String, start: Long, duration: Long) =
            p.read("""{"sentences": [], "partial": "$text", "partial_start_ms": $start, "duration_ms": $duration, "is_final": false}""")
        assertEquals(LiveAsrUpdate.Text("", "今天我们来聊一聊语音识别的问题，首先需要打开设置页面。"), partial("今天我们来聊一聊语音识别的问题，首先需要打开设置页面。", 0, 15000))
        // 新窗口开头(不算标点)在上一份里找到:前面那截掉出去了
        assertEquals(
            LiveAsrUpdate.Text("今天", "我们来聊一聊语音识别的问题，首先需要打开设置页面，然后"),
            partial("我们来聊一聊语音识别的问题，首先需要打开设置页面，然后", 1000, 16000),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一", "聊语音识别的问题。首先需要打开设置页面，然后找到"),
            partial("聊语音识别的问题。首先需要打开设置页面，然后找到", 2000, 17000),
        )
        // 窗口没挪:不多留
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一", "聊语音识别的问题，首先需要打开设置页面，然后找到模型"),
            partial("聊语音识别的问题，首先需要打开设置页面，然后找到模型", 2000, 17500),
        )
        // 这段整段放进 sentences:留下的字作废,只用服务端的句子;下一段从头来,不和上一段对齐
        val whole = "今天我们来聊一聊语音识别的问题，首先需要打开设置页面，然后找到模型。"
        assertEquals(
            LiveAsrUpdate.Text(whole, ""),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "", "partial_start_ms": 18000, "duration_ms": 18500, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text(whole, "第二段"),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "第二段", "partial_start_ms": 19000, "duration_ms": 20000, "is_final": false}"""),
        )
    }

    // 留着掉出去的字时松开:终结果只用服务端整段重解的句子,不拼留下的那截
    @Test fun finalIgnoresCarriedText() {
        val p = FunAsrProtocol()
        p.read("""{"sentences": [], "partial": "今天我们来聊一聊", "partial_start_ms": 0, "duration_ms": 15000, "is_final": false}""")
        assertEquals(
            LiveAsrUpdate.Text("今天", "我们来聊一聊语音"),
            p.read("""{"sentences": [], "partial": "我们来聊一聊语音", "partial_start_ms": 1000, "duration_ms": 16000, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别。", ""),
            p.read("""{"sentences": [{"text": "今天我们来聊一聊语音识别。", "start": 0, "end": 16500}], "partial": "", "partial_start_ms": 0, "duration_ms": 16500, "is_final": true}"""),
        )
        assertEquals(LiveAsrUpdate.Ended("今天我们来聊一聊语音识别。"), p.read("""{"event": "stopped"}"""))
    }

    // 新窗口开头在上一份里找不到(识别结果变了)时按时间比例估掉出去的那截
    @Test fun droppedPrefixFallsBackToTime() {
        assertEquals("甲乙", FunAsrProtocol.droppedPrefix("甲乙丙丁戊己庚辛壬癸", "子丑寅卯", 0, 10000, 2000))
        assertEquals("今天", FunAsrProtocol.droppedPrefix("今天，我们来", "我们来了", 0, 3000, 1000))
        assertEquals("", FunAsrProtocol.droppedPrefix("甲乙", "子丑", 0, 0, 1000))
    }

    // 没送过音频(或太短)时服务端不给终结果,直接回 stopped:当没听清
    @Test fun stoppedWithoutFinalIsEmpty() {
        assertEquals(LiveAsrUpdate.Ended(""), FunAsrProtocol().read("""{"event": "stopped"}"""))
    }

    @Test fun acknowledgementsDoNotChangeText() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "hotwords_set", "hotwords": ["Mika"]}"""))
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun latinSentencesAreJoinedWithASpace() {
        val p = FunAsrProtocol()
        val update = p.read("""{"sentences": [{"text": "Open WeChat"}, {"text": "and send a message"}], "partial": "", "is_final": true}""")
        assertEquals(LiveAsrUpdate.Text("Open WeChat and send a message", ""), update)
    }

    @Test fun addressesAreNormalized() {
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize("192.168.1.10"))
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize(" 192.168.1.10:10095 "))
        assertEquals("ws://asr.lan", FunAsrProtocol.normalize("ws://asr.lan/"))
        assertEquals("ws://asr.lan:8080/asr", FunAsrProtocol.normalize("http://asr.lan:8080/asr/"))
        assertEquals("wss://asr.example.com/ws?room=1", FunAsrProtocol.normalize("https://asr.example.com/ws?room=1"))
        assertEquals("ws://[fd00::5]:10095", FunAsrProtocol.normalize("ws://[fd00::5]:10095"))
        // 规范过的地址再规范一次不变
        for (url in listOf("ws://192.168.1.10:10095", "wss://asr.example.com/ws?room=1", "ws://[fd00::5]:10095")) {
            assertEquals(url, FunAsrProtocol.normalize(url))
        }
    }

    @Test fun badAddressesAreRejected() {
        for (bad in listOf("", "  ", "ftp://host", "ws://", "ws://user:pass@host:10095", "two words", "ws://host:port")) {
            assertNull(bad, FunAsrProtocol.normalize(bad))
        }
    }

    @Test fun hostOfDropsPathAndQuery() {
        assertEquals("192.168.1.10:10095", FunAsrProtocol.hostOf("ws://192.168.1.10:10095"))
        assertEquals("asr.example.com", FunAsrProtocol.hostOf("wss://asr.example.com/ws?token=x"))
    }
}
