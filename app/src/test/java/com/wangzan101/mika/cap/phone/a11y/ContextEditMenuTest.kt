package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEditMenuTest {
    private val menu = ContextEditMenu("Einfügen")

    @Test fun recognizesFrameworkIdOrLocalizedButton() {
        assertTrue(menu.isPaste("android:id/paste", "TextView", null))
        assertTrue(menu.isPaste(null, "Button", "Einfügen"))
    }

    @Test fun ignoresOrdinaryTextAndOtherCommands() {
        assertFalse(menu.isPaste(null, "View", "Einfügen"))
        assertFalse(menu.isPaste("some.app:id/paste", "TextView", null))
        assertFalse(menu.isPaste(null, "Button", "Einfügen und senden"))
        assertFalse(menu.isPaste(null, "Button", "Paste"))
    }

    @Test fun requiresOneNewControlAndNoOtherPasteTarget() {
        val isPaste: (String) -> Boolean = { it.startsWith("paste") }
        assertEquals("paste", menu.freshPaste(setOf("existing"), listOf("existing", "paste"), isPaste))
        assertNull(menu.freshPaste(setOf("paste"), listOf("paste"), isPaste))
        assertNull(menu.freshPaste(emptySet(), listOf("paste", "select all"), isPaste))
        assertNull(menu.freshPaste(setOf("paste-old"), listOf("paste", "paste-old"), isPaste))
        assertNull(menu.freshPaste(emptySet(), listOf("copy"), isPaste))
    }
}
