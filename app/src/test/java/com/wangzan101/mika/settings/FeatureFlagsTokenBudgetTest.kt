package com.wangzan101.mika.settings

import com.wangzan101.mika.core.usage.TokenBudgetLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureFlagsTokenBudgetTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToUnlimitedAndCanBeCleared() {
        val f = flags()
        assertNull(f.taskTokenBudget())
        f.setTaskTokenBudget(500_000)
        assertEquals(500_000L, f.taskTokenBudget())
        f.setTaskTokenBudget(null)
        assertNull(f.taskTokenBudget())
    }

    @Test fun acceptsBothBoundsAndRejectsValuesOutsideThem() {
        val f = flags()
        f.setTaskTokenBudget(TokenBudgetLimits.MIN)
        assertEquals(TokenBudgetLimits.MIN, f.taskTokenBudget())
        f.setTaskTokenBudget(TokenBudgetLimits.MAX)
        assertEquals(TokenBudgetLimits.MAX, f.taskTokenBudget())
        for (invalid in listOf(-1L, 0L, TokenBudgetLimits.MIN - 1, TokenBudgetLimits.MAX + 1)) {
            f.setTaskTokenBudget(invalid)
            assertNull("非法值按不限制处理: $invalid", f.taskTokenBudget())
        }
    }
}
