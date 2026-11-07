package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunRecordBudgetTest {
    @Test fun summaryKeepsTheStepLimit() {
        val summary = RunSummary("r", "goal", startedAtMs = 0)
            .with(RunEvent.TaskStarted(1, "goal", "model", stepLimit = 60))
        assertEquals(60, summary.stepLimit)
    }

    @Test fun oldSummaryWithoutStepLimitStillLoads() {
        val old = """{"id":"r","goal":"goal","startedAtMs":0}"""
        assertNull(RunEvent.json.decodeFromString(RunSummary.serializer(), old).stepLimit)
    }

    @Test fun stepLimitRoundTrips() {
        val summary = RunSummary("r", "goal", startedAtMs = 0, stepLimit = 120)
        val back = RunEvent.json.decodeFromString(RunSummary.serializer(), RunEvent.json.encodeToString(RunSummary.serializer(), summary))
        assertEquals(120, back.stepLimit)
    }
}
