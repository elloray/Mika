package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionOutcomeTest {
    @Test fun parsesCheckedResultAndRejectsUnclearReply() {
        assertEquals(ConditionOutcome.Checked(true, "找到新消息"),
            ConditionOutcome.parse("""{"met":true,"evidence":"找到新消息"}"""))
        assertEquals(ConditionOutcome.Checked(false, null),
            ConditionOutcome.parse("```json\n{\"met\":false}\n```"))
        assertTrue(ConditionOutcome.parse("暂时没有") is ConditionOutcome.Error)
        assertTrue(ConditionOutcome.parse("""{"met":"false"}""") is ConditionOutcome.Error)
    }

    @Test fun readsObservedValueForNextComparison() {
        assertEquals(ConditionOutcome.Checked(false, "航班页最低价", "最低 ¥620"),
            ConditionOutcome.parse("""{"met":false,"value":"  最低 ¥620 ","evidence":"航班页最低价"}"""))
        assertEquals("620", (ConditionOutcome.parse("""{"met":true,"value":620}""") as ConditionOutcome.Checked).value)
        assertEquals(null, (ConditionOutcome.parse("""{"met":true,"value":null}""") as ConditionOutcome.Checked).value)
        assertEquals(null, (ConditionOutcome.parse("""{"met":true,"value":"  "}""") as ConditionOutcome.Checked).value)
        val long = ConditionOutcome.parse("""{"met":true,"value":"${"价".repeat(200)}"}""") as ConditionOutcome.Checked
        assertEquals(ConditionOutcome.VALUE_MAX, long.value!!.length)
    }

    // evidence 是 JSON null 时当作没有,不是字符串 "null"
    @Test fun nullEvidenceIsAbsent() {
        assertEquals(ConditionOutcome.Checked(true, null), ConditionOutcome.parse("""{"met":true,"evidence":null}"""))
    }

    // 观测值进计划页和下一次的提示:换行、制表合成一个空格,去掉方向控制等格式字符
    @Test fun observedValueIsFlattenedAndStripped() {
        assertEquals("最低 ¥620 (含税)", (ConditionOutcome.parse("{\"met\":true,\"value\":\"最低\\n¥620\\t(含税)\"}") as ConditionOutcome.Checked).value)
        assertEquals("620", (ConditionOutcome.parse("{\"met\":true,\"value\":\"\\u202E620\\u200B\"}") as ConditionOutcome.Checked).value)
        assertEquals("最低 ¥620 含税", (ConditionOutcome.parse("{\"met\":true,\"value\":\"最低\\u3000¥620\\u2028含税\\u00A0\"}") as ConditionOutcome.Checked).value)
        assertEquals(null, (ConditionOutcome.parse("""{"met":true,"value":{"a":1}}""") as ConditionOutcome.Checked).value)
        assertEquals(null, (ConditionOutcome.parse("""{"met":true,"value":[1]}""") as ConditionOutcome.Checked).value)
    }

    // 按字符截断:emoji 不会被切成半个
    @Test fun observedValueIsCutByCharactersNotCodeUnits() {
        val v = (ConditionOutcome.parse("{\"met\":true,\"value\":\"${"😀".repeat(100)}\"}") as ConditionOutcome.Checked).value!!
        assertEquals(ConditionOutcome.VALUE_MAX, v.codePointCount(0, v.length))
        assertFalse(Character.isHighSurrogate(v.last()))
    }
}
