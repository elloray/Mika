package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTriggerStoreTest {
    @Test fun multipleClocksSurviveClaimsAndReopenWithoutChangingLegacySchema() {
        val file = Files.createTempDirectory("trigger-times").resolve("triggers.json").toFile()
        val now = java.time.Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("multi", "thread", "remind", now,
            reminderText = "break", calendarTime = "08:00", calendarZone = "Asia/Shanghai",
            calendarTimes = listOf("08:00", "12:30", "20:00")))
        store.claimDue(now)
        val restored = FileTriggerStore(file)
        assertEquals(listOf("08:00", "12:30", "20:00"), restored.get("multi")!!.calendarClocks())
        assertEquals(java.time.Instant.parse("2026-11-10T04:30:00Z").toEpochMilli(), restored.get("multi")!!.nextAtMs)
        assertEquals(1, restored.windows().size)
        assertEquals(false, file.readText().contains("calendarTimes"))
        assertEquals("08:00", Json.decodeFromString<TriggerSnapshot>(file.readText()).triggers.single().calendarTime)
    }

    @Test fun invalidOrStaleClockExtensionFallsBackToTheLegacyClock() {
        val file = Files.createTempDirectory("trigger-times").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("multi", "thread", "check", 1000,
            calendarTime = "08:00", calendarZone = "UTC", calendarTimes = listOf("08:00", "20:00")))
        val sidecar = File(file.parentFile, file.name + ".conditions")
        sidecar.writeText("""{"states":{"multi":{"calendarTimes":["08:00","bad"]}}}""")
        assertEquals(listOf("08:00"), FileTriggerStore(file).get("multi")!!.calendarClocks())
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
        sidecar.writeText("""{"states":{"multi":{"calendarTimes":["09:00","20:00"]}}}""")
        assertEquals(listOf("08:00"), FileTriggerStore(file).get("multi")!!.calendarClocks())
    }

    @Test fun claimAndLedgerSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val first = FileTriggerStore(file)
        first.upsert(TriggerDefinition("hourly", "thread", "check", 1_000, 1_000))
        first.claimDue(3_100)
        val restored = FileTriggerStore(file)
        assertEquals(4_000L, restored.get("hourly")!!.nextAtMs)
        assertEquals(2L, restored.windows().single().missedIntervals)
        assertEquals(TriggerWindowState.Claimed, restored.windows().single().state)
        assertEquals(emptyList<Any>(), restored.claimDue(3_100))
    }

    @Test fun reminderAndCalendarSettingsSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("daily", "thread", "remind", 1_000,
            reminderText = "该喝水了", calendarTime = "09:00", calendarZone = "Asia/Shanghai",
            calendarDays = listOf(1, 5)))
        val restored = FileTriggerStore(file).get("daily")!!
        assertEquals("该喝水了", restored.reminderText)
        assertEquals("09:00", restored.calendarTime)
        assertEquals(listOf(1, 5), restored.calendarDays)
    }

    @Test fun conditionStateSurvivesStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("price", "thread", "告诉我", 1_000, conditionGoal = "降价超过 50",
            notifyWhen = ConditionNotify.EachTimeMet, cooldownMs = 60_000, lastValue = "¥680",
            baselineValue = "¥700", baselineAtMs = 10, lastFiredAtMs = 10))
        val restored = FileTriggerStore(file).get("price")!!
        assertEquals(ConditionNotify.EachTimeMet, restored.notifyWhen)
        assertEquals(60_000L, restored.cooldownMs)
        assertEquals("¥680", restored.lastValue)
        assertEquals("¥700", restored.baselineValue)
        assertEquals(10L, restored.baselineAtMs)
        assertEquals(10L, restored.lastFiredAtMs)
    }

    // 升级前存下的计划文件没有新字段:照常读,按默认值算
    @Test fun planFileFromBeforeConditionStateStillLoads() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        file.writeText("""{"triggers":[{"id":"old","threadId":"thread","goal":"告诉我","nextAtMs":1000,"conditionGoal":"下雨","lastConditionMet":true}],"windows":[]}""")
        val restored = FileTriggerStore(file).get("old")!!
        assertEquals(true, restored.lastConditionMet)
        assertEquals(ConditionNotify.BecomesMet, restored.notifyWhen)
        assertEquals(null, restored.baselineValue)
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }

    @Test fun oldReaderCanStillReadEveryPlanAfterConditionStateIsSaved() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("fixed", "thread", "提醒", 1_000, reminderText = "喝水"))
        store.upsert(TriggerDefinition("price", "thread", "告诉我", 2_000, conditionGoal = "降价",
            notifyWhen = ConditionNotify.EachTimeMet, cooldownMs = 60_000, baselineValue = "¥700"))

        // 用旧版同样的严格解码读取主文件；扩展字段必须只在 sidecar 中，不能让旧版清空全部计划。
        val oldSchema = Json.decodeFromString<TriggerSnapshot>(file.readText())
        assertEquals(setOf("fixed", "price"), oldSchema.triggers.map { it.id }.toSet())
        assertEquals(false, file.readText().contains("baselineValue"))
        assertEquals(ConditionNotify.EachTimeMet, FileTriggerStore(file).get("price")!!.notifyWhen)
    }

    @Test fun damagedConditionSidecarDoesNotDamageMainPlans() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("fixed", "thread", "提醒", 1_000, reminderText = "喝水"))
        val sidecar = File(file.parentFile, file.name + ".conditions")
        sidecar.writeText("not json")

        val restored = FileTriggerStore(file)
        assertEquals("fixed", restored.all().single().id)
        assertEquals(true, File(file.parentFile, sidecar.name + ".corrupt").isFile)
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }
}
