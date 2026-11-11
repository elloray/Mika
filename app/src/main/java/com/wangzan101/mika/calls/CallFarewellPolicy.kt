package com.wangzan101.mika.calls

/** Accepts only an unambiguous closing from a caller's final utterance. No transcript is retained. */
internal object CallFarewellPolicy {
    private const val THANKS = "谢谢(?:你|您)?(?:啊|了)?|多谢|辛苦了"
    private const val COURTESY = "好(?:的|吧|啊)?|行(?:吧|啊)?|嗯{1,3}|那(?:好吧?)?|$THANKS"
    private const val CLOSING_CONTEXT = "(?:那)?就(?:先)?这样吧|(?:我这边|我)?没(?:有)?别的事了|不打扰了|(?:改天|回头)再聊"
    private const val GOODBYE = "(?:再见|拜拜)(?:啊|啦|了)?"
    private const val SELF_HANGUP = "(?:我(?:们|这边)?)(?:就)?(?:先)?挂(?:断)?(?:电话)?了(?:啊|啦|吧)?"
    private const val REQUEST_HANGUP = "(?:(?:麻烦|请)(?:你|您)?(?:帮我)?(?:先)?(?:挂断(?:电话)?(?:吧|了|一下)?|挂(?:一下)?电话(?:吧|了)?)|" +
        "(?:你|您)(?:可以|先)?挂(?:断)?(?:电话)?(?:了|吧|了吧)|" +
        "(?:可以|请)(?:直接)?结束(?:这次)?通话(?:了|吧)?|" +
        "(?:可以|请)(?:直接)?挂断(?:电话)?(?:了|吧)?|挂(?:断)?电话吧|挂断吧|可以挂了)"

    private val splitClause = Regex("[,，、。.!！；;]+")
    private val spaces = Regex("[ \\t\\r\\n　]+")
    private val closing = Regex("(?:$COURTESY){0,4}(?:(?:$GOODBYE){1,2}|$SELF_HANGUP|$REQUEST_HANGUP)(?:$THANKS)?")
    private val beforeClosing = Regex("(?:$COURTESY)|(?:$CLOSING_CONTEXT)")
    private val afterClosing = Regex(THANKS)
    private val quoteOrQuestion = "?？\"'“”‘’「」『』《》〈〉＂＇"
    private val reportedSpeech = Regex(
        "例如|比如|举例|示例|假如|假设|假设说|引用|原话|(?:的|这)句(?:话)?|" +
            "(?:可以|可|建议|不妨|就|应该|请|要|试着|用来)(?:先|直接|跟\\S{1,8}|对\\S{1,8}|这么|这样)?(?:说|回答|回复|念)|" +
            "(?:他说|她说|对方说|他当时说|她当时说|转述|复述)[：:]?",
    )

    private val settledEnding = Regex(
        "(?:那|好|行)?(?:我们)?(?:就(?:先)?这样|先这样|(?:就|先)(?:到这里|聊到这里|说到这里))(?:吧|了)?|" +
            "(?:那)?(?:我)?(?:就)?(?:先)?不打扰(?:您|你)?了|祝您(?:顺利|生活愉快)",
    )

    /** A natural closing sentence, never a lone acknowledgement, a question or quoted words. */
    fun isAssistantClosing(text: String): Boolean {
        if (isExplicitCallerFarewell(text)) return true
        if (text.length !in 1..180 || text.any { it in quoteOrQuestion }) return false
        val normalized = text.replace(spaces, "")
        if (reportedSpeech.containsMatchIn(normalized)) return false
        val ending = normalized.split(splitClause).lastOrNull(String::isNotEmpty) ?: return false
        return settledEnding.matches(ending)
    }

    fun isExplicitCallerFarewell(text: String): Boolean {
        if (text.length !in 1..100 || text.any { it in quoteOrQuestion }) return false
        val clauses = text.split(splitClause).map { it.replace(spaces, "") }.filter(String::isNotEmpty)
        if (clauses.size !in 1..8) return false

        var foundClosing = false
        for (clause in clauses) {
            when {
                closing.matches(clause) -> foundClosing = true
                !foundClosing && beforeClosing.matches(clause) -> Unit
                foundClosing && afterClosing.matches(clause) -> Unit
                else -> return false
            }
        }
        return foundClosing
    }
}
