package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.CALL_ID_FIELD
import com.wangzan101.mika.core.calls.CALL_TASK_FIELD
import com.wangzan101.mika.core.calls.MAX_CALL_ID_LENGTH
import com.wangzan101.mika.core.calls.parsePhoneCallTask
import com.wangzan101.mika.core.calls.phoneCallTaskSchema
import com.wangzan101.mika.core.tool.ArgError
import kotlinx.serialization.json.*

/** These tools cannot turn on automatic answering or set credentials: opt-in stays on the phone. */
internal class McpCallTools(private val calls: PhoneCallPort) {
    private fun schema(name: String) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            if (name == DIAL) {
                putJsonObject("number") { put("type", "string"); put("minLength", 3); put("maxLength", 32) }
                putJsonObject("sim_id") { put("type", "string"); put("description", "SIM id returned by mika_call_state; required when no unambiguous default exists.") }
                put(CALL_TASK_FIELD, phoneCallTaskSchema)
            }
            if (name == ANSWER || name == HANGUP) putJsonObject(CALL_ID_FIELD) {
                put("type", "string"); put("minLength", 1); put("maxLength", MAX_CALL_ID_LENGTH)
                put("description", "Exact call_id returned by mika_call_state for this call.")
            }
        }
        putJsonArray("required") {
            when (name) {
                DIAL -> add("number")
                ANSWER, HANGUP -> add(CALL_ID_FIELD)
                else -> Unit
            }
        }
        put("additionalProperties", false)
    }
    val tools = listOf(
        McpTaskTools.Tool(STATE, "Read native cellular call status, the current call_id when available, available SIMs, and AI call readiness. Never returns API keys.", schema(STATE), true),
        McpTaskTools.Tool(DIAL, "Place one cellular call to a number explicitly requested by the user, using the phone's locally enabled AI calling feature. If the user gave an objective, include it as task, with their questions, background and limits when supplied. Carrier and Volcengine charges may apply. Never dial emergency, USSD or MMI codes. A submitted dial request is not proof of connection; follow mika_call_state. Never retry a dial after an uncertain response without checking state.", schema(DIAL), false),
        McpTaskTools.Tool(ANSWER, "Answer the ringing cellular call identified by call_id from mika_call_state with the phone's locally configured Volcengine AI assistant. Requires local opt-in and ready credentials. If the call changed, do not answer the replacement automatically.", schema(ANSWER), false),
        McpTaskTools.Tool(HANGUP, "Hang up the cellular call identified by call_id from mika_call_state. Use only at the user's request or under their agreed call policy. If the call changed, do not hang up the replacement automatically.", schema(HANGUP), false),
        McpTaskTools.Tool(TAKEOVER, "Stop AI audio and give the active call back to the handset without hanging up.", schema(TAKEOVER), false),
    )

    suspend fun call(name: String, args: JsonObject): JsonObject {
        val allowed = when (name) {
            DIAL -> setOf("number", "sim_id", CALL_TASK_FIELD)
            ANSWER, HANGUP -> setOf(CALL_ID_FIELD)
            else -> emptySet()
        }
        if (args.keys.any { it !in allowed }) throw McpTaskTools.ToolError("Unexpected call tool argument")
        return when (name) {
            STATE -> calls.state()
            DIAL -> {
                val number = args["number"] as? JsonPrimitive
                if (number == null || !number.isString || number.content.length > 32 || number.content.trim().length < 3) {
                    throw McpTaskTools.ToolError("number must be a phone number string")
                }
                val sim = args["sim_id"]?.let {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() && p.content.length <= 200 }?.content
                        ?: throw McpTaskTools.ToolError("sim_id must be a SIM id from mika_call_state")
                }
                val task = try { parsePhoneCallTask(args[CALL_TASK_FIELD]) } catch (e: ArgError) {
                    throw McpTaskTools.ToolError(e.message ?: "Invalid task")
                }
                calls.dial(number.content, sim, task)
            }
            ANSWER -> calls.answer(callId(args))
            HANGUP -> calls.hangup(callId(args))
            TAKEOVER -> calls.takeover()
            else -> throw McpTaskTools.ToolError("Unknown call tool")
        }
    }

    private fun callId(args: JsonObject): String = (args[CALL_ID_FIELD] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= MAX_CALL_ID_LENGTH }?.content
        ?: throw McpTaskTools.ToolError("call_id must be the ID returned by mika_call_state")

    companion object {
        const val STATE = "mika_call_state"
        const val DIAL = "mika_call_dial"
        const val ANSWER = "mika_call_answer"
        const val HANGUP = "mika_call_hangup"
        const val TAKEOVER = "mika_call_takeover"
    }
}
