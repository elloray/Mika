package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PlanEditorDialogTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private fun show(original: TriggerDefinition? = null, saved: MutableList<TriggerDefinition>) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlanEditorDialog(original, "new-thread", onDismiss = {}, onSave = saved::add)
            }
        }
    }

    @Test fun createsFromVisibleFormWithThreePreviewTimes() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)

        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-preview-0").assertExists()
        compose.onNodeWithTag("plan-editor-interval").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-interval-minutes").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-interval-minutes").performTextInput("90")
        compose.onNodeWithTag("plan-editor-preview-2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("检查天气", plan.goal)
        assertEquals("new-thread", plan.threadId)
        assertNotEquals("new-thread", plan.id)
        assertEquals(90 * 60_000L, plan.intervalMs)
    }

    @Test fun editsExistingPlanWithoutChangingOwnership() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-content").performTextClearance()
        compose.onNodeWithTag("plan-editor-content").performTextInput("新任务")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-clock").performTextInput("09:30")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("existing-id", plan.id)
        assertEquals("existing-thread", plan.threadId)
        assertEquals("新任务", plan.goal)
        assertEquals("09:30", plan.calendarTime)
        assertEquals("已经准备好", plan.conditionGoal)
    }

    @Test fun switchingToFixedReminderRemovesCondition() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-reminder").performClick()
        compose.onNodeWithTag("plan-editor-condition").assertDoesNotExist()
        compose.onNodeWithTag("plan-editor-save").performClick()

        val plan = saved.single()
        assertEquals("旧任务", plan.reminderText)
        assertEquals("旧任务", plan.goal)
        assertNull(plan.conditionGoal)
    }

    @Test fun invalidTimeStaysInEditorUntilCorrected() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-first-at").performTextClearance()
        compose.onNodeWithTag("plan-editor-first-at").performTextInput("2020-01-01 00:00")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithTag("plan-editor-error").assertExists()
        assertTrue(saved.isEmpty())

        compose.onNodeWithTag("plan-editor-first-at").performTextClearance()
        compose.onNodeWithTag("plan-editor-first-at").performTextInput("2099-01-01 00:00")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, saved.size)
    }

    @Test fun plansPageHasManualCreationEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlansScreen(context, onBack = {}, onOpenConversation = {})
            }
        }

        compose.onNodeWithContentDescription("新建定时任务").performClick()
        compose.onNodeWithTag("plan-editor-content").assertIsDisplayed()
    }
}
