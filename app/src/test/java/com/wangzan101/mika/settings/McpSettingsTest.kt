package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = McpSettings(tmp.root, AesGcmKeyCipher { key })

    @Test fun offByDefaultAndTheSwitchSurvivesARestart() {
        assertFalse(settings().enabled.value)
        assertEquals(McpSettings.DEFAULT_PORT, settings().port)
        settings().setEnabled(true)
        assertTrue(settings().enabled.value)
        settings().setEnabled(false)
        assertFalse(settings().enabled.value)
    }

    @Test fun historyConsentIsOffForOldSettingsAndPersistsIndependently() {
        val original = settings()
        original.setEnabled(true)
        assertFalse(original.historyEnabled.value)
        original.setHistoryEnabled(true)
        assertTrue(settings().historyEnabled.value)
        original.setEnabled(false)
        assertTrue(settings().historyEnabled.value)
        original.setHistoryEnabled(false)
        assertFalse(settings().historyEnabled.value)
        assertFalse(settings().enabled.value)
    }

    @Test fun unreadableFileMeansOff() {
        tmp.root.resolve("mcp.json").writeText("{oops")
        assertFalse(settings().enabled.value)
    }

    @Test fun tokenIsRandomStableAndStoredEncrypted() {
        val token = settings().token()
        assertTrue(token.length >= 40)
        assertEquals(token, settings().token())
        // 文件里是密文,读不出原文
        val raw = tmp.root.resolve("mcp-token.bin").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(token))
        // 换一个钥匙(比如重装后钥匙库没了)就读不出旧令牌,另生成一个
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNotEquals(token, McpSettings(tmp.root, AesGcmKeyCipher { other }).token())
    }

    @Test fun regenerateReplacesTheToken() {
        val s = settings()
        val old = s.token()
        val fresh = s.regenerate()
        assertNotEquals(old, fresh)
        assertEquals(fresh, s.token())
        assertEquals(fresh, settings().token())
    }

    @Test fun portChangePersistsAndReloads() {
        val s = settings()
        assertEquals(McpSettings.DEFAULT_PORT, s.port)
        s.setPort(9123)
        assertEquals(9123, s.port)
        // 重读(下次启动)照旧,开关等别的设置不受影响
        s.setEnabled(true)
        val reloaded = settings()
        assertEquals(9123, reloaded.port)
        assertTrue(reloaded.enabled.value)
    }

    @Test fun portOutsideRangeIsRejected() {
        val s = settings()
        for (bad in listOf(1023, 65536, 0, -1)) {
            try {
                s.setPort(bad); fail("port $bad should be rejected")
            } catch (_: IllegalArgumentException) {}
        }
        assertEquals(McpSettings.DEFAULT_PORT, s.port)
        assertEquals(McpSettings.DEFAULT_PORT, settings().port)
    }

    @Test fun handEditedPortInFileStillRead() {
        // 改动前只能手工编辑 mcp.json 里的 port;这种存量配置继续按它读
        tmp.root.resolve("mcp.json").writeText("""{"enabled":false,"port":9123,"historyEnabled":false}""")
        assertEquals(9123, settings().port)
    }
}
