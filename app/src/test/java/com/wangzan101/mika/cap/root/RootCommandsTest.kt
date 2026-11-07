package com.wangzan101.mika.cap.root

import com.wangzan101.mika.core.cap.root.RootRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandsTest {
    /** 任务已经拿到端口后再关掉设置,旧端口也不能再启动 su,更不能把它记成授权被拒绝。 */
    @Test
    fun turningTheSettingOffStopsAnExistingPort() = runBlocking {
        var suCalls = 0
        var refusalCalls = 0
        val commands = RootCommands(
            su = { suCalls++; "/system/xbin/su" },
            allowed = { false },
            refused = { refusalCalls++ },
        )

        assertEquals(RootRun.Denied, commands.run("id", 1_000))
        assertEquals(0, suCalls)
        assertEquals(0, refusalCalls)
    }
}
