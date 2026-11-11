package com.wangzan101.mika.calls

import kotlinx.serialization.Serializable
import java.util.Collections

/** The owner's objective for a single outgoing call. It does not record a business outcome. */
@Serializable
data class CallTaskBrief(
    val goal: String,
    val questions: List<String> = emptyList(),
    val context: String = "",
    val constraints: String = "",
) {
    /** Validate before dialing and detach the pending call from any mutable form inputs. */
    fun validatedSnapshot(): CallTaskBrief {
        val normalizedGoal = goal.trim()
        val normalizedContext = context.trim()
        val normalizedConstraints = constraints.trim()
        require(normalizedGoal.length in 1..MAX_GOAL_CHARS) { "本次目标需为 1–400 字" }
        require(questions.size <= MAX_QUESTIONS) { "本次最多可添加 8 项必问事项" }
        val normalizedQuestions = questions.map(String::trim)
        require(normalizedQuestions.all { it.length in 1..MAX_QUESTION_CHARS }) { "每项必问事项需为 1–200 字" }
        require(normalizedContext.length <= MAX_CONTEXT_CHARS) { "本次背景最多 1000 字" }
        require(normalizedConstraints.length <= MAX_CONSTRAINT_CHARS) { "本次边界最多 600 字" }
        return CallTaskBrief(normalizedGoal, Collections.unmodifiableList(normalizedQuestions),
            normalizedContext, normalizedConstraints)
    }

    override fun toString(): String = "CallTaskBrief(questions=${questions.size})"

    companion object {
        const val MAX_GOAL_CHARS = 400
        const val MAX_QUESTIONS = 8
        const val MAX_QUESTION_CHARS = 200
        const val MAX_CONTEXT_CHARS = 1_000
        const val MAX_CONSTRAINT_CHARS = 600
    }
}
