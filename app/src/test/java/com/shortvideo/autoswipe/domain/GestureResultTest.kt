package com.shortvideo.autoswipe.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureResultTest {
    @Test
    fun aMissingCallbackTimesOutWithoutReportingSuccess() = runTest {
        val result = async { awaitGestureResult { awaitCancellation() } }
        runCurrent()
        advanceTimeBy(2_499); runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertTrue(result.await() is GestureOutcome.Blocked)
    }

    @Test
    fun stoppingTheParentStillPropagatesCancellation() = runTest {
        var outcome: GestureOutcome? = null
        val job = launch { outcome = awaitGestureResult { awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        assertNull(outcome)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aTimelyCallbackKeepsItsOriginalResult() = runTest {
        val result = awaitGestureResult { delay(350); GestureOutcome.Completed }
        assertEquals(GestureOutcome.Completed, result)
    }
}
