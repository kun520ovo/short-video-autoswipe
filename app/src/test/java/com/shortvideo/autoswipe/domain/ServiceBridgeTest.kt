package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.model.TaskConfig
import com.shortvideo.autoswipe.platform.AccessibilityBackend
import com.shortvideo.autoswipe.platform.ServiceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceBridgeTest {
    private val target = TargetApp("video.app", "video.app/.Main", "测试视频")

    private inner class Backend : AccessibilityBackend {
        var inspectionFailure: RuntimeException? = null
        var availabilityFailure: RuntimeException? = null
        var foregroundFailure: RuntimeException? = null
        var swipes = 0
        override fun availability(): SafetyCheck {
            availabilityFailure?.let { throw it }
            return SafetyCheck.Ready
        }
        override fun inspect(target: TargetApp): SafetyCheck {
            inspectionFailure?.let { throw it }
            return SafetyCheck.Ready
        }
        override fun foregroundTarget(): TargetApp {
            foregroundFailure?.let { throw it }
            return target
        }
        override suspend fun swipe(target: TargetApp): GestureOutcome {
            swipes++
            return GestureOutcome.Completed
        }
        override suspend fun doubleTap(target: TargetApp) = GestureOutcome.Completed
        override fun refreshOverlay() = Unit
    }

    @Test
    fun aWindowQueryFailurePausesTheTaskWithoutBreakingEnvironmentUpdates() = runTest {
        val backend = Backend()
        val bridge = ServiceBridge().apply { attach(backend) }
        val engine = AutomationEngine(
            bridge, backgroundScope, StandardTestDispatcher(testScheduler),
            MonotonicClock { testScheduler.currentTime },
        )
        val environmentJob = backgroundScope.launch {
            bridge.changes.collect { engine.environmentChanged() }
        }
        engine.start(TaskConfig("测试视频", 1, 10, 10), target) { Result.success(Unit) }
        runCurrent()
        advanceTimeBy(12_000)
        runCurrent()
        backend.inspectionFailure = IllegalStateException("PRIVATE_WINDOW_DATA")
        bridge.changed()
        runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertFalse(engine.waitingForAutoResume)
        assertTrue(environmentJob.isActive)
        assertFalse(requireNotNull(engine.state.value.pauseReason).contains("PRIVATE_WINDOW_DATA"))
        assertEquals(8_000L, requireNotNull(engine.state.value.session).waitRemainingMs)

        backend.inspectionFailure = null
        assertEquals(CommandResult.Accepted, engine.resume())
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(1, backend.swipes)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun anAvailabilityQueryFailureRejectsStartingWithoutLaunchingTheApp() = runTest {
        val backend = Backend().apply { availabilityFailure = SecurityException("PRIVATE_PERMISSION_DATA") }
        val bridge = ServiceBridge().apply { attach(backend) }
        val engine = AutomationEngine(
            bridge, backgroundScope, StandardTestDispatcher(testScheduler),
            MonotonicClock { testScheduler.currentTime },
        )
        var launched = false
        val result = engine.start(TaskConfig(), target) { launched = true; Result.success(Unit) }
        assertTrue(result is CommandResult.Rejected)
        assertFalse(launched)
        assertFalse(engine.state.value.locked)
        assertFalse((result as CommandResult.Rejected).message.contains("PRIVATE_PERMISSION_DATA"))
    }

    @Test
    fun aForegroundQueryFailureKeepsTheTaskPaused() = runTest {
        val backend = Backend()
        val bridge = ServiceBridge().apply { attach(backend) }
        val engine = AutomationEngine(
            bridge, backgroundScope, StandardTestDispatcher(testScheduler),
            MonotonicClock { testScheduler.currentTime },
        )
        engine.start(TaskConfig(), target) { Result.success(Unit) }
        runCurrent()
        engine.pause()
        backend.foregroundFailure = IllegalStateException("PRIVATE_WINDOW_DATA")
        assertTrue(engine.resume() is CommandResult.Rejected)
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, backend.swipes)
    }

    @Test
    fun coroutineCancellationIsNotConvertedIntoAPermissionFailure() {
        val backend = Backend().apply { availabilityFailure = CancellationException("cancelled") }
        val bridge = ServiceBridge().apply { attach(backend) }
        assertThrows(CancellationException::class.java) { bridge.availability() }
    }
}
