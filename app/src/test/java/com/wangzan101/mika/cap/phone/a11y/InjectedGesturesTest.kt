package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InjectedGesturesTest {
    @Test fun cancellationWhileReturningFromIsolationRestoresTouch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            lateinit var request: Job
            val gestures = InjectedGestures { isolated ->
                changes += isolated
                if (isolated) request.cancel()
            }
            request = launch(start = CoroutineStart.LAZY) {
                gestures.passThrough { error("A cancelled request must not inject a gesture") }
            }
            request.start()
            runCurrent()
            assertTrue(request.isCompleted)
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
            assertEquals(42, gestures.passThrough { 42 })
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false, true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellationBeforeIsolationDoesNotReleaseAnotherGesture() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            val gestures = InjectedGestures { changes += it }
            val finish = CompletableDeferred<Unit>()
            val first = launch { gestures.passThrough { finish.await() } }
            runCurrent()
            val pending = launch(UnconfinedTestDispatcher(testScheduler)) {
                gestures.passThrough { error("The cancelled request never acquired isolation") }
            }
            pending.cancel()
            runCurrent()
            assertTrue(pending.isCompleted)
            assertTrue(gestures.isolated)
            assertEquals(listOf(true), changes)
            finish.complete(Unit)
            first.join()
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellationAfterInjectionWaitsForTheGestureToFinish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            val gestures = InjectedGestures { changes += it }
            val finish = CompletableDeferred<Unit>()
            val request = launch { gestures.passThrough { finish.await() } }
            runCurrent()
            request.cancel()
            runCurrent()
            assertFalse(request.isCompleted)
            assertTrue(gestures.isolated)
            finish.complete(Unit)
            request.join()
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun nestedGesturesRestoreTouchOnlyAfterTheOuterGesture() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            val gestures = InjectedGestures { changes += it }
            val result = gestures.passThrough {
                gestures.passThrough { assertTrue(gestures.isolated) }
                assertTrue(gestures.isolated)
                42
            }
            assertEquals(42, result)
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun overlappingGesturesKeepIsolationUntilBothFinish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            val gestures = InjectedGestures { changes += it }
            val finishFirst = CompletableDeferred<Unit>()
            val finishSecond = CompletableDeferred<Unit>()
            val first = launch { gestures.passThrough { finishFirst.await() } }
            val second = launch { gestures.passThrough { finishSecond.await() } }
            runCurrent()
            finishSecond.complete(Unit)
            second.join()
            assertTrue(gestures.isolated)
            assertEquals(listOf(true), changes)
            finishFirst.complete(Unit)
            first.join()
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failureWhileIsolatingWindowsAlsoRestoresTouch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val changes = mutableListOf<Boolean>()
            val gestures = InjectedGestures {
                changes += it
                if (it) error("Window isolation failed after updating a window")
            }
            val failure = runCatching { gestures.passThrough { error("Must not inject") } }.exceptionOrNull()
            assertEquals("Window isolation failed after updating a window", failure?.message)
            assertFalse(gestures.isolated)
            assertEquals(listOf(true, false), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun restorationFailureDoesNotReplaceTheOriginalFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gestures = InjectedGestures { isolated ->
                error(if (isolated) "Window isolation failed" else "Window restoration failed")
            }
            val failure = runCatching { gestures.passThrough { error("Must not inject") } }.exceptionOrNull()
            assertEquals("Window isolation failed", failure?.message)
            assertEquals("Window restoration failed", failure?.suppressed?.single()?.message)
            assertFalse(gestures.isolated)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun oneWindowFailureDoesNotLeaveLaterWindowsIsolated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val states = mutableMapOf(0 to false, 1 to false, 2 to false)
            val attempts = mutableListOf<Pair<Int, Boolean>>()
            val gestures = InjectedGestures { isolated ->
                forEachGestureWindow(states.keys.toList()) { window ->
                    attempts += window to isolated
                    states[window] = isolated
                    if (window == 1 && !isolated) error("One window could not be restored")
                }
            }
            val failure = runCatching { gestures.passThrough { 42 } }.exceptionOrNull()
            assertEquals("One window could not be restored", failure?.message)
            assertFalse(gestures.isolated)
            assertEquals(setOf(false), states.values.toSet())
            assertEquals(
                listOf(0 to true, 1 to true, 2 to true, 0 to false, 1 to false, 2 to false),
                attempts,
            )
        } finally { Dispatchers.resetMain() }
    }
}
