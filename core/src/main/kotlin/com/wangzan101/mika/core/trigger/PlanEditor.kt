package com.wangzan101.mika.core.trigger

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** 手动编辑器和模型编辑共用的重复方式。 */
enum class PlanRepeat { Once, Interval, Daily, Weekly }

enum class PlanValidation {
    MissingContent,
    ContentTooLong,
    ConditionTooLong,
    ReminderWithCondition,
    InvalidFirstTime,
    TimeNotFuture,
    InvalidInterval,
    InvalidClock,
    InvalidZone,
    InvalidWeekdays,
    InvalidCooldown,
}

class PlanValidationException(val reason: PlanValidation) : IllegalArgumentException(reason.name)

/** 页面上的原始输入集中在这里，预览和最终保存必须走同一次转换规则。 */
data class PlanDraft(
    val content: String,
    val reminder: Boolean = false,
    val condition: String = "",
    val repeat: PlanRepeat = PlanRepeat.Once,
    val firstAt: String,
    val intervalMinutes: String = "60",
    val clock: String = "09:00",
    val zone: String,
    val weekdays: Set<Int> = emptySet(),
    val whenIdle: Boolean = false,
    val enabled: Boolean = true,
    val notifyWhen: ConditionNotify = ConditionNotify.BecomesMet,
    val cooldownMinutes: String = "",
)

private val EDIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)

fun formatPlanEditorTime(atMs: Long, zone: String): String =
    EDIT_TIME.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.of(zone)))

fun newPlanDraft(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): PlanDraft {
    val first = Instant.ofEpochMilli(nowMs).atZone(zone).plusHours(1).withSecond(0).withNano(0)
    return PlanDraft(
        content = "",
        firstAt = EDIT_TIME.format(first),
        zone = zone.id,
        weekdays = setOf(first.dayOfWeek.value),
    )
}

fun TriggerDefinition.toPlanDraft(): PlanDraft {
    val zone = calendarZone ?: ZoneId.systemDefault().id
    return PlanDraft(
        content = reminderText ?: goal,
        reminder = reminderText != null,
        condition = conditionGoal.orEmpty(),
        repeat = when {
            calendarTime != null && calendarDays.isEmpty() -> PlanRepeat.Daily
            calendarTime != null -> PlanRepeat.Weekly
            intervalMs != null -> PlanRepeat.Interval
            else -> PlanRepeat.Once
        },
        firstAt = formatPlanEditorTime(nextAtMs, zone),
        intervalMinutes = intervalMs?.let { (it / 60_000).toString() } ?: "60",
        clock = calendarClocks().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "09:00",
        zone = zone,
        weekdays = calendarDays.toSet(),
        whenIdle = idleOnly,
        enabled = enabled,
        notifyWhen = notifyWhen,
        cooldownMinutes = cooldownMs?.let { (it / 60_000).toString() }.orEmpty(),
    )
}

/**
 * 把表单变成可持久化的计划。编辑时只替换表单负责的字段，保留 ID、对话和执行账本；
 * 条件语义改变后旧值不能继续作为新条件的比较基线。
 */
fun editedTrigger(
    draft: PlanDraft,
    original: TriggerDefinition?,
    newId: String,
    newThreadId: String,
    nowMs: Long,
): TriggerDefinition {
    val content = draft.content.trim()
    if (content.isEmpty()) invalid(PlanValidation.MissingContent)
    if (content.length > if (draft.reminder) 2_000 else 4_000) invalid(PlanValidation.ContentTooLong)
    val condition = draft.condition.trim().ifEmpty { null }
    if ((condition?.length ?: 0) > 2_000) invalid(PlanValidation.ConditionTooLong)
    if (draft.reminder && condition != null) invalid(PlanValidation.ReminderWithCondition)
    val zone = try { ZoneId.of(draft.zone.trim()) } catch (e: Exception) {
        invalid(PlanValidation.InvalidZone)
    }
    val nextAt: Long
    var intervalMs: Long? = null
    var calendarTime: String? = null
    var calendarTimes: List<String> = emptyList()
    var calendarZone: String? = null
    var calendarDays: List<Int> = emptyList()
    when (draft.repeat) {
        PlanRepeat.Once, PlanRepeat.Interval -> {
            nextAt = try {
                // 模型沿用 ISO 时间以保留秒和偏移；页面继续使用当地 yyyy-MM-dd HH:mm。
                runCatching { Instant.parse(draft.firstAt.trim()).toEpochMilli() }.getOrElse {
                    LocalDateTime.parse(draft.firstAt.trim(), EDIT_TIME).atZone(zone).toInstant().toEpochMilli()
                }
            } catch (e: Exception) {
                invalid(PlanValidation.InvalidFirstTime)
            }
            if (draft.repeat == PlanRepeat.Interval) {
                val minutes = draft.intervalMinutes.trim().toLongOrNull()
                if (minutes == null || minutes !in 1..525_600) invalid(PlanValidation.InvalidInterval)
                intervalMs = minutes * 60_000
            }
            val unchangedTime = original != null && original.calendarTime == null &&
                original.nextAtMs == nextAt && original.intervalMs == intervalMs &&
                (!draft.enabled || original.enabled)
            if (nextAt <= nowMs && !unchangedTime) invalid(PlanValidation.TimeNotFuture)
        }
        PlanRepeat.Daily, PlanRepeat.Weekly -> {
            val days = if (draft.repeat == PlanRepeat.Weekly) draft.weekdays.sorted() else emptyList()
            if (draft.repeat == PlanRepeat.Weekly && (days.isEmpty() || days.any { it !in 1..7 })) {
                invalid(PlanValidation.InvalidWeekdays)
            }
            val clocks = try {
                normalizedCalendarTimes(draft.clock.split(',', '，', '、', ';', '；', '،', '\n'))
            } catch (e: Exception) {
                invalid(PlanValidation.InvalidClock)
            }
            nextAt = nextCalendarAt(clocks, zone.id, days, nowMs)
            calendarTime = clocks.first()
            calendarTimes = clocks.takeIf { it.size > 1 }.orEmpty()
            calendarZone = zone.id
            calendarDays = days
        }
    }
    val cooldownMs = if (condition != null && draft.cooldownMinutes.isNotBlank()) {
        val minutes = draft.cooldownMinutes.trim().toLongOrNull()
        if (minutes == null || minutes !in 1..525_600) invalid(PlanValidation.InvalidCooldown)
        minutes * 60_000
    } else null
    val reminderText = content.takeIf { draft.reminder }
    val base = original ?: TriggerDefinition(newId, newThreadId, content, nextAt)
    val updated = base.copy(
        goal = content,
        nextAtMs = nextAt,
        intervalMs = intervalMs,
        conditionGoal = condition,
        enabled = draft.enabled,
        idleOnly = draft.whenIdle,
        reminderText = reminderText,
        calendarTime = calendarTime,
        calendarZone = calendarZone,
        calendarDays = calendarDays,
        calendarTimes = calendarTimes,
        notifyWhen = draft.notifyWhen,
        cooldownMs = cooldownMs,
    )
    return if (original == null) updated else updated.withConditionHistoryFrom(original)
}

private fun invalid(reason: PlanValidation): Nothing = throw PlanValidationException(reason)
