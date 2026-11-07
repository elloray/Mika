package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardFieldsTest {
    @Test fun stockKeyguardSelfDrawnPinFieldDoesNotNeedEditable() {
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/pinEntry",
            "com.android.keyguard.PasswordTextView", true, false, false))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/pinEntry",
            "com.android.keyguard.PasswordTextView", true, false, false))
    }

    @Test fun editablePasswordAndPinHaveDistinctTypes() {
        assertTrue(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/passwordEntry", "android.widget.EditText", true, true, false))
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/pinEntry", "android.widget.EditText", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/pinEntry", "android.widget.EditText", true, true, true))
    }

    @Test fun unknownCustomFieldsAndSimPinsAreNeverEntered() {
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/simPinEntry", "com.android.keyguard.PasswordTextView", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "other:id/pinEntry", "com.android.keyguard.PasswordTextView", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/unknown", "CustomPasswordView", true, false, false))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/passwordEntry", "android.widget.EditText", false, true, false))
    }

    @Test fun untypedFieldIsPinWhenTheWindowHasADigitPadAndPasswordOtherwise() {
        val id = "com.android.systemui:id/bouncer_text_entry"
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = true))
        assertTrue(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = false))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = false))
        // 声明了文字输入类型的密码框,旁边有数字也还是密码框
        assertTrue(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = false, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = false, digitPad = true))
    }

    @Test fun patternViewsNeverBecomeCredentialFields() {
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/lockPatternView",
            "android.view.View", false, false, false, untyped = true, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/lockPatternView",
            "android.view.View", false, false, false, untyped = true, digitPad = true))
    }

    @Test fun digitKeysAreSingleDigitsOptionallyWithLetters() {
        assertEquals('1', KeyguardFields.digitKey("1", null))
        assertEquals('2', KeyguardFields.digitKey(null, "2 ABC"))
        assertEquals('0', KeyguardFields.digitKey(" 0 ", "0 +"))
        assertNull(KeyguardFields.digitKey("12:30", null))
        assertNull(KeyguardFields.digitKey("5G", null))
        assertNull(KeyguardFields.digitKey("", "Enter"))
        assertTrue(KeyguardFields.isDigitPad(('0'..'9').toSet()))
        assertFalse(KeyguardFields.isDigitPad(('1'..'9').toSet()))
    }
}
