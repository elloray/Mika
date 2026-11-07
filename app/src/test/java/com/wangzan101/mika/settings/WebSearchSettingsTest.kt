package com.wangzan101.mika.settings

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsTest {
    private val identityCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    @Test fun freshInstallIsOffWithoutAnEndpoint() {
        val settings = WebSearchSettings(MemoryPrefs(), identityCipher)
        assertEquals(WebSearchConfig(), settings.get())
        assertNull(settings.service())
    }

    @Test fun configurationAndEncryptedKeySurviveRestart() {
        val prefs = MemoryPrefs()
        val expected = WebSearchConfig(true, "https://example.com/search", "secret")
        WebSearchSettings(prefs, identityCipher).set(expected)
        assertEquals(expected, WebSearchSettings(prefs, identityCipher).get())
        assertTrue(WebSearchSettings(prefs, identityCipher).service() != null)
        assertFalse(prefs.getString("api_key_encrypted", null)!!.contains("secret"))
    }

    @Test fun disabledOrInvalidConfigurationCannotCreateAService() {
        val prefs = MemoryPrefs()
        val settings = WebSearchSettings(prefs, identityCipher)
        settings.set(WebSearchConfig(false, "https://example.com/search", "key"))
        assertNull(settings.service())
        prefs.edit().putBoolean("enabled", true).putString("endpoint", "not a URL").commit()
        assertNull(WebSearchSettings(prefs, identityCipher).service())
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledConfigurationRequiresAValidEndpoint() {
        WebSearchSettings(MemoryPrefs(), identityCipher).set(WebSearchConfig(true, "", ""))
    }
}
