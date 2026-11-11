package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.wechat.WechatChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUnlockPolicyTest {
    private val peer = ChannelPeer(WechatChannel.ID, "owner", "thread")

    @Test fun scheduledTasksKeepUnlockWithoutResolvingWechat() {
        val authorized = taskUnlockAuthorization(RunOrigin.Trigger, null) {
            error("A scheduled task must not initialize WeChat")
        }

        assertTrue(requireNotNull(authorized).invoke())
    }

    @Test fun otherTaskOriginsCannotUseWechatPermission() {
        for (origin in listOf(RunOrigin.Ui, RunOrigin.Mcp, RunOrigin.Child)) {
            assertNull(taskUnlockAuthorization(origin, peer) {
                error("$origin must not read WeChat permission")
            })
        }
    }

    @Test fun missingOrOtherChannelPeersNeverResolveWechat() {
        for (candidate in listOf(null, peer.copy(channelId = "local"), peer.copy(channelId = "mcp"))) {
            assertNull(taskUnlockAuthorization(RunOrigin.Channel, candidate) {
                error("A non-WeChat peer must not read WeChat permission")
            })
        }
    }

    @Test fun grantingPermissionLaterDoesNotChangeAManualWaitIntoAnUnlockAttempt() {
        var allowed = false
        var checks = 0
        val canUnlock: (ChannelPeer?) -> Boolean = { checks++; allowed }
        val waitingTask = taskUnlockAuthorization(RunOrigin.Channel, peer, canUnlock)
        assertNull(waitingTask)
        assertEquals(1, checks)

        allowed = true
        assertNull(waitingTask)
        assertEquals(1, checks)
        val nextTask = taskUnlockAuthorization(RunOrigin.Channel, peer, canUnlock)
        assertTrue(requireNotNull(nextTask).invoke())
    }

    @Test fun authorizedWechatTaskRechecksItsPeerAfterPermissionIsRevoked() {
        var allowed = true
        val checkedPeers = mutableListOf<ChannelPeer?>()
        val authorized = requireNotNull(taskUnlockAuthorization(RunOrigin.Channel, peer) {
            checkedPeers += it
            allowed
        })
        assertEquals(listOf(peer), checkedPeers)
        assertTrue(authorized())

        allowed = false
        assertFalse(authorized())
        assertEquals(listOf(peer, peer, peer), checkedPeers)
    }
}
