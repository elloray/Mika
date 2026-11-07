package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanEditorTest {
    private val now = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
    private val zone = "Asia/Shanghai"

    private fun draft() = newPlanDraft(now, java.time.ZoneId.of(zone)).copy(content = "check the weather")

    @Test fun createsOneShotWithIndependentIdentityAndMatchingPreview() {
        val input = draft().copy(firstAt = "2026-11-07 08:30")
        val plan = editedTrigger(input, null, "new-plan", "new-thread", now)

        assertEquals("new-plan", plan.id)
        assertEquals("new-thread", plan.threadId)
        assertEquals("check the weather", plan.goal)
        assertEquals(Instant.parse("2026-11-07T00:30:00Z").toEpochMilli(), plan.nextAtMs)
        assertNull(plan.intervalMs)
        assertNull(plan.calendarTime)
        assertTrue(plan.enabled)
        assertEquals(listOf(plan.nextAtMs), upcoming(plan, now, 3))
    }

    @Test fun createsIntervalAndCalendarRulesInTheirOwnZone() {
        val interval = editedTrigger(draft().copy(
            repeat = PlanRepeat.Interval,
            firstAt = "2026-11-07 08:30",
            intervalMinutes = "90",
        ), null, "interval", "thread", now)
        assertEquals(90 * 60_000L, interval.intervalMs)
        assertEquals(
            listOf(interval.nextAtMs, interval.nextAtMs + 90 * 60_000L, interval.nextAtMs + 180 * 60_000L),
            upcoming(interval, now, 3),
        )

        val weekly = editedTrigger(draft().copy(
            repeat = PlanRepeat.Weekly,
            clock = "09:15",
            weekdays = setOf(1, 5),
            zone = "America/New_York",
        ), null, "weekly", "thread", now)
        assertEquals("09:15", weekly.calendarTime)
        assertEquals("America/New_York", weekly.calendarZone)
        assertEquals(listOf(1, 5), weekly.calendarDays)
        assertEquals(3, upcoming(weekly, now, 3).size)
        assertNull(weekly.intervalMs)
    }

    @Test fun editsWithoutChangingOwnershipOrUnrelatedConditionHistory() {
        val original = TriggerDefinition(
            "plan", "thread", "old", now + 60_000,
            conditionGoal = "price is below 10",
            enabled = false,
            idleOnly = true,
            lastConditionMet = false,
            lastValue = "12",
            baselineValue = "11",
            baselineAtMs = 123,
            lastFiredAtMs = 100,
            cooldownMs = 300_000,
        )
        val edited = editedTrigger(original.toPlanDraft().copy(
            content = "new goal",
            firstAt = "2026-11-08 10:00",
        ), original, "ignored", "ignored", now)

        assertEquals("plan", edited.id)
        assertEquals("thread", edited.threadId)
        assertEquals("new goal", edited.goal)
        assertFalse(edited.enabled)
        assertTrue(edited.idleOnly)
        assertEquals(false, edited.lastConditionMet)
        assertEquals("12", edited.lastValue)
        assertEquals("11", edited.baselineValue)
        assertEquals(123L, edited.baselineAtMs)
        assertEquals(100L, edited.lastFiredAtMs)
    }

    @Test fun changedConditionStartsWithFreshComparisonState() {
        val original = TriggerDefinition(
            "plan", "thread", "goal", now + 60_000,
            conditionGoal = "old condition",
            lastConditionMet = true,
            lastValue = "old",
            baselineValue = "base",
            baselineAtMs = 123,
            lastFiredAtMs = 100,
        )
        val edited = editedTrigger(original.toPlanDraft().copy(condition = "new condition"), original, "x", "y", now)

        assertEquals("new condition", edited.conditionGoal)
        assertNull(edited.lastConditionMet)
        assertNull(edited.lastValue)
        assertNull(edited.baselineValue)
        assertNull(edited.baselineAtMs)
        assertNull(edited.lastFiredAtMs)
    }

    @Test fun fixedReminderUsesOneTextAndRejectsCondition() {
        val reminder = editedTrigger(draft().copy(
            content = "take a break",
            reminder = true,
            firstAt = "2026-11-07 08:30",
        ), null, "reminder", "thread", now)
        assertEquals("take a break", reminder.goal)
        assertEquals("take a break", reminder.reminderText)
        assertNull(reminder.conditionGoal)

        assertInvalid(draft().copy(reminder = true, condition = "something changed"), PlanValidation.ReminderWithCondition)
    }

    @Test fun rejectsInvalidTimesRangesAndWeekdays() {
        assertInvalid(draft().copy(content = " "), PlanValidation.MissingContent)
        assertInvalid(draft().copy(firstAt = "2026-02-30 08:30"), PlanValidation.InvalidFirstTime)
        assertInvalid(draft().copy(firstAt = "2026-11-05 08:30"), PlanValidation.TimeNotFuture)
        assertInvalid(draft().copy(zone = "not/a zone"), PlanValidation.InvalidZone)
        assertInvalid(draft().copy(repeat = PlanRepeat.Interval, intervalMinutes = "0"), PlanValidation.InvalidInterval)
        assertInvalid(draft().copy(repeat = PlanRepeat.Daily, clock = "25:00"), PlanValidation.InvalidClock)
        assertInvalid(draft().copy(repeat = PlanRepeat.Weekly, weekdays = emptySet()), PlanValidation.InvalidWeekdays)
        assertInvalid(draft().copy(condition = "ready", cooldownMinutes = "-1"), PlanValidation.InvalidCooldown)
    }

    @Test fun conditionOptionsAreClearedWithCondition() {
        val original = TriggerDefinition(
            "plan", "thread", "goal", now + 60_000,
            conditionGoal = "ready",
            notifyWhen = ConditionNotify.EachTimeMet,
            cooldownMs = 600_000,
            lastValue = "yes",
        )
        val edited = editedTrigger(original.toPlanDraft().copy(condition = ""), original, "x", "y", now)

        assertNull(edited.conditionGoal)
        assertNull(edited.cooldownMs)
        assertNull(edited.lastValue)
    }

    private fun assertInvalid(input: PlanDraft, expected: PlanValidation) {
        val error = runCatching { editedTrigger(input, null, "id", "thread", now) }.exceptionOrNull()
        assertTrue("expected $expected but got $error", error is PlanValidationException)
        assertEquals(expected, (error as PlanValidationException).reason)
    }
}
