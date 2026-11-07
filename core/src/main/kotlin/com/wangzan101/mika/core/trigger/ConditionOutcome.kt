package com.wangzan101.mika.core.trigger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 条件检查的机器结果；无法解析和执行失败都不能当成条件不成立。 */
sealed interface ConditionOutcome {
    /** [value] 是下次拿来比的关键值(如「最低 ¥620」),检查没给时为 null。 */
    data class Checked(val met: Boolean, val evidence: String?, val value: String? = null) : ConditionOutcome
    data class Error(val reason: String) : ConditionOutcome

    companion object {
        const val VALUE_MAX = 80
        private const val EVIDENCE_MAX = 500

        fun parse(answer: String): ConditionOutcome {
            val raw = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return Error("条件检查没有返回可读取的结果")
            val met = (body["met"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return Error("条件检查结果缺少 met 状态")
            val evidence = text(body["evidence"])?.take(EVIDENCE_MAX)
            val value = text(body["value"])?.let(::normalize)?.ifEmpty { null }
            return Checked(met, evidence, value)
        }

        /** 字符串、数字、布尔照原样取;JSON null、对象、数组当作没有。 */
        private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        private val FORMAT = Regex("\\p{Cf}")
        // 安卓的正则(ICU)不认 (?U) 这类内联标记;用 \p{Z} 收全角空格、不换行空格和行/段分隔符
        private val BLANK = Regex("[\\s\\p{Cc}\\p{Z}]+")

        /** 观测值会进计划页和下一次的检查指令:去掉格式字符,空白与控制字符合成一个空格,按字符(码点)截到 [VALUE_MAX]。 */
        private fun normalize(raw: String): String {
            val flat = raw.replace(FORMAT, "").replace(BLANK, " ").trim()
            if (flat.codePointCount(0, flat.length) <= VALUE_MAX) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, VALUE_MAX)).trimEnd()
        }
    }
}
