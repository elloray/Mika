package com.wangzan101.mika.core.calls

import com.wangzan101.mika.core.tool.ArgError
import java.util.Collections
import kotlinx.serialization.json.*

/** Owner instructions for one outgoing call. They do not change the phone's saved profile. */
data class PhoneCallTask(
    val goal: String,
    val questions: List<String> = emptyList(),
    val context: String = "",
    val constraints: String = "",
) {
    override fun toString() = "PhoneCallTask(questions=${questions.size})"
}

// These limits match the Android CallTaskBrief used by the native call runtime.
private const val GOAL_LIMIT = 400
private const val QUESTION_COUNT_LIMIT = 8
private const val QUESTION_LIMIT = 200
private const val CONTEXT_LIMIT = 1_000
private const val CONSTRAINT_LIMIT = 600

internal const val CALL_TASK_FIELD = "task"
private val fields = setOf("goal", "questions", "context", "constraints")

internal val phoneCallTaskSchema = buildJsonObject {
    put("type", "object")
    put("description", "The user's instructions for this call only. Include task whenever the user provided an objective. Do not invent instructions.")
    putJsonObject("properties") {
        put("goal", textSchema("The user's goal for this call.", GOAL_LIMIT, min = 1))
        putJsonObject("questions") {
            put("type", "array")
            put("description", "Specific questions the user asked the assistant to ask, in order.")
            put("maxItems", QUESTION_COUNT_LIMIT)
            put("items", textSchema("One question to ask.", QUESTION_LIMIT, min = 1))
        }
        put("context", textSchema("Relevant background the user supplied for this call.", CONTEXT_LIMIT))
        put("constraints", textSchema("Limits the user gave, including what must not be promised or shared.", CONSTRAINT_LIMIT))
    }
    putJsonArray("required") { add("goal") }
    put("additionalProperties", false)
}

private fun textSchema(description: String, max: Int, min: Int = 0) = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (min > 0) put("minLength", min)
    put("maxLength", max)
}

/** A missing task keeps old clients working; an invalid supplied task must never become a plain dial. */
internal fun parsePhoneCallTask(value: JsonElement?): PhoneCallTask? {
    if (value == null) return null
    val task = value as? JsonObject ?: throw ArgError(CALL_TASK_FIELD, "must be an object")
    if (task.keys.any { it !in fields }) throw ArgError(CALL_TASK_FIELD, "only goal, questions, context and constraints are allowed")
    val goal = parseText(task["goal"], "task.goal", GOAL_LIMIT, min = 1)
    val questions = task["questions"]?.let { supplied ->
        val items = supplied as? JsonArray ?: throw ArgError("task.questions", "must be an array of strings")
        if (items.size > QUESTION_COUNT_LIMIT) throw ArgError("task.questions", "must contain at most $QUESTION_COUNT_LIMIT items")
        Collections.unmodifiableList(items.map { parseText(it, "task.questions", QUESTION_LIMIT, min = 1) })
    }.orEmpty()
    return PhoneCallTask(
        goal, questions,
        parseText(task["context"], "task.context", CONTEXT_LIMIT),
        parseText(task["constraints"], "task.constraints", CONSTRAINT_LIMIT),
    )
}

private fun parseText(value: JsonElement?, field: String, max: Int, min: Int = 0): String {
    if (value == null && min == 0) return ""
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?: throw ArgError(field, "must be a string")
    if (text.length < min) throw ArgError(field, "must not be blank")
    if (text.length > max) throw ArgError(field, "must be at most $max characters")
    return text
}
