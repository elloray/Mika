package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    /** 找不到模型:知道模型名时说清是哪个、去哪改、模型名大概长什么样;不知道时退回一般的说法。 */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "模型「gemini」在这个服务商那里不存在。去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("找不到该模型,请到设置 › 模型检查模型名和 Base URL 是否正确。", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("找不到模型「gemini」", ModelError.ModelNotFound("gemini").message)
    }

    /** 404 也可能是网关、中转服务在说别的:有原话时照原话说、不断定模型不存在,句末的点不重复;没有原话时和上面一样。 */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "调用模型「group/rpa」时服务端回复：none of group/rpa's models is ready。可能是模型名不对，或者服务那边暂时不可用。" +
                "去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "服务端回复：The model `x` does not exist。可能是模型名不对，或者服务那边暂时不可用。请到设置 › 模型检查模型名和 Base URL 是否正确。",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said.contains("不存在"))
    }

    /** 引在句中的服务端原话,三种带原话的失败都只去掉末尾一个句号;截短的省略号不动。 */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed。") && !it.contains("Not allowed.")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许。") && !it.contains("不允许。。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed。")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long…。")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait...。")) }
    }

    /** 这个账号用不了的模型、不支持工具的模型:说清是哪个、去哪换,或到服务商后台开通。 */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "模型「qwen-max」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "模型「gemma」不支持调用工具,Mika 干活要靠工具。去设置 › 模型换一个会调用工具的模型。",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** 思考强度被服务端拒绝时保留参数错误并指向档位设置,不再误报 Base URL 或接口类型。 */
    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("思考强度改为默认"))
        assertFalse(reason.contains("Base URL"))
        assertFalse(reason.contains("接口类型"))
    }

    /** 断流有自己的说法、不提 Base URL;真的解析不了(格式对不上)才提示检查 Base URL 和接口类型。 */
    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("连接就在上游被断开了"))
        assertTrue(cut.contains("重试后"))
        assertFalse(cut.contains("Base URL"))
        assertFalse(cut.contains("接口类型"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("流式响应不是合法 JSON")).reason.contains("请检查 Base URL 和接口类型是否匹配"))
    }
}
