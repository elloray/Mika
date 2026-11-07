package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/** 对话页上按状态算出来的文字;不碰界面,单测直接测。 */
object ChatCopy {
    /** 空对话的问候,按时段。 */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)

    /** 空对话里的示例;点一下填进输入框,用户改好了再发。[kind] 决定配哪个线条图标。 */
    enum class ExampleKind { App, Web, File, Schedule, Device }
    data class Example(val kind: ExampleKind, val text: String)

    val examples get() = listOf(
        Example(ExampleKind.App, tr(R.string.chat_example_follow)),
        Example(ExampleKind.App, tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Web, tr(R.string.chat_example_jobs)),
        Example(ExampleKind.App, tr(R.string.chat_example_multi_step)),
        Example(ExampleKind.Device, tr(R.string.chat_example_alarms)),
        Example(ExampleKind.App, tr(R.string.chat_example_ai_apps)),
    )

    /** 对话页顶栏的标题:固定是 App 名;副标题说此刻在做什么。 */
    const val APP_TITLE = "Mika"

    /** 对话的名字:第一句话(只取第一行,附件那几行不算;只发了屏幕时是「你此刻的屏幕」);空对话为空。侧栏里用它称呼对话。 */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * 一句用户的话当标题怎么写:去掉网页说明([AttachedPage])、引用([QuoteMessage])、附件那几行(只发了附件时是「照片」「合同.pdf」之类)和屏幕那一行(只发了屏幕时是「你此刻的屏幕」),
     * 只取第一行。
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(QuoteMessage.words(AttachedPage.words(text)))).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * 分支的标题:源标题(或第一句话)后面加本地化后缀,总长不超过 [max] 字。
     * 截的是源标题,后缀留着;按码点截,不会把 emoji 截成半个。
     */
    fun branchTitle(base: String, suffix: String, max: Int = 24): String {
        val room = (max - suffix.length).coerceAtLeast(0)
        var end = 0
        var taken = 0
        while (end < base.length && taken < room) {
            end += Character.charCount(base.codePointAt(end))
            taken++
        }
        return base.substring(0, end).trimEnd() + suffix
    }

    enum class Tone { Accent, Warn, Ok }

    /** 顶栏副标题:这个对话留着一页在后台运行(没有任务在跑时)。 */
    val KEPT_PAGE: String get() = tr(R.string.kept_chat_sub)

    /** 顶栏副标题:正在做什么(强调色)或等你(橙色);没有任务在跑时为 null。[place] 是正在操作的 App 或网站。 */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.Queued -> tr(R.string.chat_queued) to Tone.Accent
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** 进度那一行:在跑时是此刻的进展,停下来等你时是「已暂停」。 */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** 任务停下来等你(要你处理、回答、同意、解锁、点继续)。 */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** 进度行右边的用时:「0:24」「12:05」「1:02:03」。 */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 在跑时步骤只列最近这几步,前面的收成一行「前面还有 n 步」,点开看全部。 */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** 在后台操作手机时步骤下面那行小字。 */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    /**
     * 一个动作的结果(记录里的 effect 代号 → 给人看的一句话)。错误码只对开发有意义,
     * 给人看的一律说「没成功」。运行过程页用的是同一份说法。
     */
    fun effectLabel(effect: String?): String = when (effect) {
        "changed", "done", "place_changed" -> tr(R.string.result_effective)
        "small_change" -> tr(R.string.effect_small_change)
        "unchanged", "no_effect" -> tr(R.string.result_unchanged)
        "completed" -> tr(R.string.result_completed)
        null, "" -> ""
        else -> tr(R.string.step_failed)
    }

    /** 点开一步后每件事一行:做了什么 · 对什么 · 结果(结果为空时不写)。 */
    fun actionLine(a: StepAction): String =
        listOfNotNull(a.label, a.target?.takeIf(String::isNotBlank), effectLabel(a.effect).takeIf(String::isNotBlank)).joinToString(" · ")

    fun placeholder(mode: ComposerMode, hasHistory: Boolean, followUp: FollowUpMode = FollowUpMode.Steer): String = when (mode) {
        ComposerMode.Busy -> tr(if (followUp == FollowUpMode.Queue) R.string.chat_placeholder_queue else R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }

    /** 能按住说话时的居中灰字:告诉人能按住说、点一下打字;任务在跑时说的就是插话或排队。 */
    fun voicePlaceholder(mode: ComposerMode, followUp: FollowUpMode = FollowUpMode.Steer): String = when (mode) {
        ComposerMode.Busy -> tr(if (followUp == FollowUpMode.Queue) R.string.voice_placeholder_queue else R.string.voice_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.voice_placeholder_answer)
        ComposerMode.Idle -> tr(R.string.voice_placeholder_idle)
    }
}
