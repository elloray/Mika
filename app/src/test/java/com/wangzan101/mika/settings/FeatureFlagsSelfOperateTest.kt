package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「允许 Mika 操作自己的界面」默认关,只能用户改;它不在模型能改的设置白名单里。 */
class FeatureFlagsSelfOperateTest {
    @Test fun defaultsOffAndToggles() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertFalse(flags.selfOperate())
        flags.setSelfOperate(true)
        assertTrue(flags.selfOperate())
    }

    @Test fun theModelCannotTurnItOnThroughSettings() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val keys = SelfConfigImpl(flags).options().map { it.key }
        assertFalse("self_operate" in keys || "self_config" in keys)
    }
}
