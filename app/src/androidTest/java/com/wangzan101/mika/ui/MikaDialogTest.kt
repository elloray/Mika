package com.wangzan101.mika.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.components.MikaNoticeDialog
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MikaDialogTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test
    fun noticeRendersMarkdownAndKeepsDismissAction() {
        var dismissed by mutableStateOf(false)
        compose.setContent {
            MikaTheme {
                if (!dismissed) MikaNoticeDialog("已完成 **整理**\n\n- 第一项\n- 第二项", onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("已完成 整理").assertIsDisplayed()
        compose.onNodeWithText("第一项").assertIsDisplayed()
        compose.onNodeWithText("第二项").assertIsDisplayed()
        compose.onNodeWithText("已完成 **整理**").assertDoesNotExist()
        compose.onNodeWithText("知道了").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
        compose.onNodeWithText("已完成 整理").assertDoesNotExist()
    }
}
