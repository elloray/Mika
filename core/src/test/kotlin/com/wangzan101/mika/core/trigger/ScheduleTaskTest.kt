package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTaskTest {
    @Test fun userCanListAndPauseOwnPlanFromConversation() = runBlocking {
        var paused = false
        val plan = TriggerDefinition("plan-1", "conversation", "天气提醒", 1000, reminderText = "带伞")
        val admin = object : ScheduleAdmin {
            override fun list(threadId: String) = if (threadId == "conversation") listOf(plan) else emptyList()
            override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
                paused = threadId == "conversation" && id == "plan-1" && !enabled
                return paused
            }
            override fun remove(threadId: String, id: String) = false
            override fun reschedule(threadId: String, id: String, atMs: Long) = false
            override fun runNow(threadId: String, id: String) = false
        }
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleAdmin = admin)
        val listed = tools.execute(Part.ToolCall("l", "schedule_task", """{"op":"list"}"""), ActionPass())
        assertTrue(listed.content.toString().contains("plan-1"))
        val changed = tools.execute(Part.ToolCall("p", "schedule_task",
            """{"op":"pause","triggerId":"plan-1"}"""), ActionPass())
        assertEquals(true, changed.content["changed"]?.toString()?.toBoolean())
        assertTrue(paused)
    }

    // 计划在、恢复却没改成(跑过的一次性计划):如实说已经跑过,不说找不到
    @Test fun resumingAPlanThatAlreadyRanSaysSoInsteadOfNotFound() = runBlocking {
        val plan = TriggerDefinition("plan-1", "conversation", "查快递", 1000)
        val admin = object : ScheduleAdmin {
            override fun list(threadId: String) = if (threadId == "conversation") listOf(plan) else emptyList()
            override fun setEnabled(threadId: String, id: String, enabled: Boolean) = false
            override fun remove(threadId: String, id: String) = false
            override fun reschedule(threadId: String, id: String, atMs: Long) = false
            override fun runNow(threadId: String, id: String) = false
        }
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleAdmin = admin)
        val ran = tools.execute(Part.ToolCall("r", "schedule_task", """{"op":"resume","triggerId":"plan-1"}"""), ActionPass())
        assertEquals("false", ran.content["changed"]?.toString())
        assertEquals("\"already_ran\"", ran.content["reason"]?.toString())
        val missing = tools.execute(Part.ToolCall("m", "schedule_task", """{"op":"resume","triggerId":"plan-2"}"""), ActionPass())
        assertEquals("\"plan_not_found\"", missing.content["reason"]?.toString())
    }

    @Test fun userRunCanPersistExplicitFutureTask() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        assertTrue(tools.definitions().any { it.name == "schedule_task" })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00","intervalMinutes":1440}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals("conversation", saved?.threadId)
        assertEquals(86_400_000L, saved?.intervalMs)
    }

    // 没有准时闹钟权限:照样存下,但结果里明说,模型据此告诉用户,不等到点才悄悄失败
    @Test fun missingExactAlarmPermissionIsReportedAtCreation() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(false, userNotified = true) })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals(false, result.content["exactAlarm"]?.toString()?.toBoolean())
        assertEquals(true, result.content["userNotified"]?.toString()?.toBoolean())
        assertTrue(result.content["message"].toString().contains("alarms & reminders"))
        assertTrue(saved != null)

        // 这次没发出提示(提示过了或通知被关):结果照实说
        val silent = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { Scheduled(false) })
            .execute(Part.ToolCall("s", "schedule_task", """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertEquals(false, silent.content["userNotified"]?.toString()?.toBoolean())
        assertTrue(silent.content["message"].toString().contains("has not been shown"))

        val granted = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { Scheduled(true) })
            .execute(Part.ToolCall("s", "schedule_task", """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertTrue(granted.content["exactAlarm"] == null)
    }

    @Test fun fixedReminderAndLocalClockRepeatAreSavedWithoutModelWork() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"提醒喝水","reminder":"该喝水了","repeatAt":"09:00","repeatZone":"Asia/Shanghai","weekdays":["MON","FRI"]}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals("该喝水了", saved?.reminderText)
        assertEquals("09:00", saved?.calendarTime)
        assertEquals(listOf(1, 5), saved?.calendarDays)
        assertTrue(saved!!.nextAtMs > System.currentTimeMillis())
    }

    @Test fun conditionPlanKeepsNotifyModeAndCooldown() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"告诉我要下雨","repeatAt":"08:00","condition":"今天会下雨吗","notifyWhen":"each_time_met","cooldownMinutes":90}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals(ConditionNotify.EachTimeMet, saved?.notifyWhen)
        assertEquals(90 * 60_000L, saved?.cooldownMs)

        var plain: TriggerDefinition? = null
        toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { plain = it; Scheduled(true) })
            .execute(Part.ToolCall("s", "schedule_task", """{"goal":"降价告诉我","repeatAt":"08:00","condition":"比基准降价超过 50 元"}"""), ActionPass())
        assertEquals(ConditionNotify.BecomesMet, plain?.notifyWhen)
        assertEquals(null, plain?.cooldownMs)
    }

    @Test fun conditionOptionsNeedAConditionAndAKnownMode() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        val orphan = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"查天气","repeatAt":"08:00","cooldownMinutes":60}"""), ActionPass())
        assertEquals("\"condition_option_without_condition\"", orphan.content["reason"]?.toString())
        val orphanMode = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"查天气","repeatAt":"08:00","notifyWhen":"each_time_met"}"""), ActionPass())
        assertEquals("\"condition_option_without_condition\"", orphanMode.content["reason"]?.toString())
        val unknown = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"查天气","repeatAt":"08:00","condition":"下雨","notifyWhen":"sometimes"}"""), ActionPass())
        assertEquals("\"invalid_notify_when\"", unknown.content["reason"]?.toString())
        assertEquals(null, saved)
    }

    @Test fun listShowsConditionModeAndObservedValues() = runBlocking {
        val plan = TriggerDefinition("plan-1", "conversation", "降价告诉我", 1000, conditionGoal = "比基准降价超过 50 元",
            cooldownMs = 3_600_000, lastValue = "最低 ¥680", baselineValue = "最低 ¥700", baselineAtMs = 500)
        val admin = object : ScheduleAdmin {
            override fun list(threadId: String) = if (threadId == "conversation") listOf(plan) else emptyList()
            override fun setEnabled(threadId: String, id: String, enabled: Boolean) = false
            override fun remove(threadId: String, id: String) = false
            override fun reschedule(threadId: String, id: String, atMs: Long) = false
            override fun runNow(threadId: String, id: String) = false
        }
        val listed = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleAdmin = admin)
            .execute(Part.ToolCall("l", "schedule_task", """{"op":"list"}"""), ActionPass())
        val text = listed.content.toString()
        assertTrue(text, text.contains("\"notifyWhen\":\"becomes_met\""))
        assertTrue(text, text.contains("\"cooldownMinutes\":60"))
        assertTrue(text, text.contains("\"lastValue\":\"<untrusted>\\n最低 ¥680\\n</untrusted>\""))
        assertTrue(text, text.contains("\"baselineValue\":\"<untrusted>\\n最低 ¥700\\n</untrusted>\""))
    }

    @Test fun unattendedRunCannotAskOrCreateAnotherSchedule() = runBlocking {
        val spec = RunSpec(RunId("trigger"), "check", "thread", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertFalse("schedule_task" in names)
        assertFalse("ask_user" in names)
        assertFalse("request_confirmation" in names)
        assertFalse("hand_to_user" in names)
        val result = tools.execute(Part.ToolCall("s", "schedule_task", "{}"), ActionPass())
        assertEquals("unknown_tool", result.content["error"]?.toString()?.trim('"'))
    }

    @Test fun conditionRunOnlyOffersReadToolsAndWebNavigation() {
        val spec = RunSpec(RunId("condition"), "check", "thread",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true)
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertTrue("web_navigate" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "run_command" || it == "write_file" || it == "web_interact" })
    }
}
