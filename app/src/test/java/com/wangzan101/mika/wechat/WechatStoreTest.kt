package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WechatStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    @Test fun accountCursorAndPendingMessageSurviveReopen() {
        val dir = tmp.newFolder()
        val first = WechatStore(dir, cipher)
        first.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        first.setEnabled(true)
        first.rememberContext("owner", "context")
        val peer = first.peer("owner")
        assertTrue(first.journal("message-1", peer, "任务"))
        assertFalse(first.journal("message-1", peer, "任务"))
        first.saveCursor("next")

        val reopened = WechatStore(dir, cipher)
        assertEquals("secret", reopened.account()?.token)
        assertTrue(reopened.enabled())
        assertEquals("next", reopened.cursor())
        assertEquals("context", reopened.contextToken("owner"))
        assertEquals(peer, reopened.peer("owner"))
        assertEquals(listOf(PendingWechatMessage("message-1", peer, "任务")), reopened.pending())

        reopened.acknowledge("message-1")
        assertEquals(emptyList<PendingWechatMessage>(), WechatStore(dir, cipher).pending())
        assertFalse(reopened.journal("message-1", peer, "任务"))
        assertFalse(WechatStore(dir, cipher).journal("message-1", peer, "任务"))
    }

    @Test fun acknowledgementHistoryPrunesOldestFirstAndSurvivesReopen() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        val peer = ChannelPeer(WechatChannel.ID, "owner", "thread")
        val count = WechatStore.MAX_ACKNOWLEDGED_MESSAGES + 2
        repeat(count) { index ->
            val id = "message-$index"
            assertTrue(store.journal(id, peer, "任务 $index"))
            store.acknowledge(id)
        }

        val reopened = WechatStore(dir, cipher)
        assertTrue(reopened.journal("message-0", peer, "旧消息"))
        assertTrue(reopened.journal("message-1", peer, "旧消息"))
        for (index in 2 until count) {
            assertFalse("message-$index should remain deduplicated", reopened.journal("message-$index", peer, "重复"))
        }
    }

    @Test fun accountSwitchClearsPollingStateButTokenRefreshPreservesIt() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        store.saveCursor("next")
        store.rememberContext("owner", "context")
        val oldPeer = store.peer("owner")
        store.journal("old-pending", oldPeer, "待登记")
        store.journal("old-ack", oldPeer, "已登记")
        store.acknowledge("old-ack")

        store.saveAccount(WechatAccount("new-token", "https://example.test", "bot", "owner"))
        assertEquals("next", store.cursor())
        assertEquals(1, store.pending().size)
        assertFalse(store.journal("old-ack", oldPeer, "重复"))

        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "new-owner"))
        assertEquals("", store.cursor())
        assertEquals(null, store.contextToken("owner"))
        assertEquals(emptyList<PendingWechatMessage>(), store.pending())
        val newPeer = store.peer("new-owner")
        assertTrue(store.journal("old-ack", newPeer, "新账号消息"))
        assertTrue(newPeer.threadId != oldPeer.threadId)
    }

    @Test fun unbindClearsCredentialsAndChannelState() {
        val store = WechatStore(tmp.newFolder(), cipher)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        store.peer("owner")
        store.clear()
        assertEquals(null, store.account())
        assertFalse(store.enabled())
        assertEquals("", store.cursor())
    }
}
