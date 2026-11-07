package com.wangzan101.mika.chat

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.task.TriggerRuntime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机走 App 的生产接线与真实文件:删历史不删计划,保留下来的提醒只写一段新历史。 */
@RunWith(AndroidJUnit4::class)
class ConversationScheduleDeletionDeviceTest {
    @Test
    fun deletedConversationKeepsItsPlanAndStartsFreshHistoryOnNextDelivery() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val suffix = UUID.randomUUID().toString()
        val conversationId = "test-delete-schedule-$suffix"
        val planId = "test-plan-$suffix"
        val nextAt = System.currentTimeMillis() + 3_600_000
        val plan = TriggerDefinition(
            id = planId,
            threadId = conversationId,
            goal = "定时提醒",
            nextAtMs = nextAt,
            intervalMs = 86_400_000,
            reminderText = "新的提醒",
        )
        val notifications = app.getSystemService(NotificationManager::class.java)
        try {
            app.conversations.append(conversationId, Turn("旧任务", "旧结果", "succeeded"))
            app.conversations.setTitle(conversationId, "旧历史")
            TriggerRuntime.schedule(app, plan)
            assertEquals(1, app.conversationDeleter.impact(conversationId).schedules)

            app.conversationDeleter.delete(conversationId)

            assertEquals(emptyList<Turn>(), app.conversations.turns(conversationId))
            assertEquals(null, app.conversations.title(conversationId))
            assertEquals(plan, TriggerRuntime.list(app).singleOrNull { it.id == planId })

            TriggerRuntime.runNow(app, planId)

            val turns = app.conversations.turns(conversationId)
            assertEquals(1, turns.size)
            assertEquals("定时提醒", turns.single().userText)
            assertEquals("新的提醒", turns.single().outcome)
            assertTrue(turns.single().runId?.startsWith("reminder:$planId:") == true)
            assertEquals(nextAt, TriggerRuntime.list(app).single { it.id == planId }.nextAtMs)
        } finally {
            TriggerRuntime.snapshot(app).windows.filter { it.triggerId == planId }.forEach {
                notifications.cancel(it.key.hashCode())
            }
            TriggerRuntime.remove(app, planId)
            app.conversations.delete(conversationId)
        }
    }
}
