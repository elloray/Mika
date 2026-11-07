package com.wangzan101.mika.core.memory

import java.text.Normalizer

/**
 * 用户本人在这次任务里说的话:开局、插话和追问的回答里他自己的话(带的屏幕、浏览器页的页面说明、引的 Mika 的回答和附件行不算,
 * 引的他自己说过的话算)、接管交回时的备注。
 * 记忆的 `quote` 必须是其中某句的子串(全半角统一、去首尾空白后比较)——屏幕、网页、工具结果里的字不算。
 */
class UserUtterances {
    private val texts = mutableListOf<String>()
    /** 唯一可跨轮引用的原话:上一轮用户明确确认了再上一轮助手的建议/提问。 */
    private var previousConfirmation: String? = null

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    /** 本轮用户的话;自动提取也只用它,不把上一轮确认当成这轮又说了一遍。 */
    val all: List<String> @Synchronized get() = texts.toList()

    /**
     * 登记仅有的跨轮例外。[confirmation] 是上一轮用户自己的话,[proposal] 是紧挨着它之前那轮助手的回答。
     * 建议/提问与明确肯定都要由原文看出来;否定、纠错、普通陈述和更早轮次一律不登记。
     */
    @Synchronized fun addPreviousConfirmation(confirmation: String, proposal: String, proposalStatus: String) {
        previousConfirmation = null
        if (proposalStatus != "succeeded" && proposalStatus != "unverified") return
        if (!proposal.contains('?') && !proposal.contains('？') && !PROPOSAL.containsMatchIn(proposal)) return
        val text = confirmation.trim()
        if (text.isEmpty() || NEGATION.containsMatchIn(text) || !CONFIRMATION.containsMatchIn(text)) return
        previousConfirmation = text
    }

    /** 每次任务开始清空:上一个任务里说的话不算这次的,跨轮确认也必须按本次上文重新登记。 */
    @Synchronized fun clear() {
        texts.clear()
        previousConfirmation = null
    }

    /**
     * [content] 里的具体值在 [quote] 和用户本次说的话里都找不到的那些:模型转述时从屏幕上抄来的值。
     * 具体值 = 引号内的内容、数字(中文数字、分隔开的数字先统一成一串阿拉伯数字)、3 个字母以上的英文/编号、
     * 地址片段(地名标志字连同前两个字,如「张江路」「一品苑」;只取两个字,模型多补一个区名不至于误拒)。
     * 比较前两边都去掉「用户(的)」「我(的)」,免得第三人称转述本身造成误拒。
     *
     * 局限:没加引号的中文人名、无标志字的地名认不出来(「发给王小明」);这类靠提示词和结果卡片上用户过目。
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> =
        unsupportedSpecifics(content, quote, texts)

    /** memory_save 只有引文本身来自上一轮确认时才让那段话参与具体值核对;普通本轮写入保持原校验。 */
    @Synchronized fun unsupportedMemorySpecifics(content: String, quote: String): List<String> {
        val sources = if (matches(quote, texts)) texts else texts + listOfNotNull(previousConfirmation)
        return unsupportedSpecifics(content, quote, sources)
    }

    private fun unsupportedSpecifics(content: String, quote: String, sources: List<String>): List<String> {
        val c = comparable(content)
        val said = (sources + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /** 不足 2 个字的引文不算数:一两个字在哪句话里都找得到。普通写入只认本轮。 */
    @Synchronized fun contains(quote: String): Boolean = matches(quote, texts)

    /** memory_save 另认已登记的上一轮明确确认;助手建议本身永远不是可引用的用户原话。 */
    @Synchronized fun containsMemoryQuote(quote: String): Boolean =
        matches(quote, texts + listOfNotNull(previousConfirmation))

    private fun matches(quote: String, sources: List<String>): Boolean {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return false
        return sources.any { norm(it).contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        val CONFIRMATION = Regex("""^(?:(?:嗯+|恩+|呃|哦|噢|啊)[\s,，。.!！~～]*)?(?:好的?|好呀|好嘞|行|可以|对的?|是的?|没错|同意|就这样|就这么|ok(?:ay)?|yes|yeah|yep|sure|correct|exactly|agreed|sounds\s+good|go\s+ahead)(?=$|[\s,，。.!！:：;；~～])""", RegexOption.IGNORE_CASE)
        val NEGATION = Regex("""不|别|算了|取消|但是|不过|\b(?:no|not|don't|do\s+not|stop|cancel|wrong|but)\b""", RegexOption.IGNORE_CASE)
        val PROPOSAL = Regex("""建议|推荐|可以叫|叫我|命名为|起名|名字|要不要|不如|我可以|你觉得|怎么样|suggest|recommend|how\s+about|would\s+you\s+like|shall\s+(?:i|we)|you\s+can\s+call|i\s+(?:can|could)|let['’]s""", RegexOption.IGNORE_CASE)
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
    }
}
