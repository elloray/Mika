package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFollowUpTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    @Test fun modeBarDefaultsToSteerAndSwitchesToQueue() {
        var mode by mutableStateOf(FollowUpMode.Steer)
        compose.setContent { MikaTheme { FollowUpModeBar(mode, { mode = it }, ExchangeStatus.Working) } }
        compose.onNodeWithText("引导").assertIsDisplayed()
        compose.onNodeWithText("排队").assertIsDisplayed()
        compose.onNodeWithText("下一步就按这句话调整").assertIsDisplayed()
        compose.onNodeWithTag("follow-up-mode").assertIsDisplayed()
        compose.onNodeWithTag("follow-up-mode-queue").performClick()
        compose.runOnIdle { assertEquals(FollowUpMode.Queue, mode) }
        compose.onNodeWithText("当前任务结束后自动开始").assertIsDisplayed()
    }

    @Test fun trayShowsCountAndCancelsOrEdits() {
        val items = listOf(QueuedFollowUp("a", "c", "下一件"), QueuedFollowUp("b", "c", "再下一件"))
        val actions = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                FollowUpTray(items, active = true, onEdit = { actions += "edit:$it" }, onCancel = { actions += "cancel:$it" }, onStartNow = { actions += "start" })
            }
        }
        compose.onNodeWithText("排队中 · 2 件", substring = true).assertIsDisplayed()
        compose.onNodeWithText("下一件").assertIsDisplayed()
        compose.onNodeWithTag("follow-up-tray").assertIsDisplayed()
        compose.onAllNodesWithText("取消")[0].performClick()
        compose.runOnIdle { assertEquals(listOf("cancel:a"), actions) }
    }
}
