package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.TriggerDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScheduleAdminTest {
    private val first = TriggerDefinition("plan-a", "thread-a", "天气提醒", 1000)
    private val second = TriggerDefinition("plan-b", "thread-b", "工作提醒", 2000)
    private val plans = mutableListOf(first, second)
    private val calls = mutableListOf<String>()
    private val admin = LocalScheduleAdmin(
        plans = { plans.toList() },
        changeEnabled = { id, enabled -> calls += "enabled:$id:$enabled" },
        delete = { id -> calls += "delete:$id" },
        changeTime = { id, atMs -> calls += "time:$id:$atMs" },
        executeNow = { id -> calls += "run:$id" },
    )

    @Test fun listsOnlyPlansFromTheCurrentConversation() {
        assertEquals(listOf(first), admin.list("thread-a"))
        assertEquals(listOf(second), admin.list("thread-b"))
        assertTrue(admin.list("empty-thread").isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun ownPlanOperationsReachTheRuntimeWithTheirArguments() {
        assertTrue(admin.setEnabled("thread-a", first.id, false))
        assertTrue(admin.setEnabled("thread-a", first.id, true))
        assertTrue(admin.reschedule("thread-a", first.id, 3000))
        assertTrue(admin.runNow("thread-a", first.id))
        assertTrue(admin.remove("thread-a", first.id))
        assertEquals(listOf(
            "enabled:plan-a:false", "enabled:plan-a:true", "time:plan-a:3000", "run:plan-a", "delete:plan-a",
        ), calls)
    }

    @Test fun foreignAndMissingPlansNeverReachTheRuntime() {
        for (id in listOf(second.id, "missing-plan")) {
            assertFalse(admin.setEnabled("thread-a", id, false))
            assertFalse(admin.setEnabled("thread-a", id, true))
            assertFalse(admin.remove("thread-a", id))
            assertFalse(admin.reschedule("thread-a", id, 3000))
            assertFalse(admin.runNow("thread-a", id))
        }
        assertTrue(calls.isEmpty())
        assertEquals(listOf(first, second), plans)
    }

    @Test fun ownershipIsCheckedAgainstTheCurrentPlans() {
        assertEquals(listOf(first), admin.list("thread-a"))
        plans[0] = first.copy(threadId = "thread-b")
        assertTrue(admin.list("thread-a").isEmpty())
        assertFalse(admin.remove("thread-a", first.id))
        assertTrue(calls.isEmpty())
        assertTrue(admin.remove("thread-b", first.id))
        assertEquals(listOf("delete:plan-a"), calls)
    }
}
