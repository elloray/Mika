package com.wangzan101.mika.self

import com.wangzan101.mika.core.self.SelfScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 进程内自身请求:只留最新一条,处理完只清同一条。 */
class SelfUiTest {
    @Test fun latestRequestWins() {
        val ui = SelfUi()
        ui.requestOpen(screen = SelfScreen.Settings)
        val last = ui.requestOpen(screen = SelfScreen.Browser)
        assertEquals(SelfScreen.Browser, ui.request.value?.screen)
        assertEquals(last, ui.request.value?.id)
    }

    @Test fun consumedOnlyClearsTheSameRequest() {
        val ui = SelfUi()
        val first = ui.requestOpen(screen = SelfScreen.Settings)
        ui.requestOpen(screen = SelfScreen.Memory)
        ui.consumed(first)
        assertEquals(SelfScreen.Memory, ui.request.value?.screen)
        ui.consumed(ui.request.value!!.id)
        assertNull(ui.request.value)
    }

    @Test fun fileRequestCarriesConversationAndPath() {
        val ui = SelfUi()
        ui.requestOpen(file = SelfFileRequest("c1", "/workspace/out/report.pdf", "report.pdf"))
        assertEquals("c1", ui.request.value?.file?.conversationId)
        assertEquals("/workspace/out/report.pdf", ui.request.value?.file?.path)
    }
}
