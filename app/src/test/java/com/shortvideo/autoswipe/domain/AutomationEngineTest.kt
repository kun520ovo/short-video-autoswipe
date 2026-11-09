package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {
    private companion object {
        val target = TargetApp("example.video", "example.video/.MainActivity", "测试视频")
    }
    private fun config(count: Int = 2, seconds: Int = 1, like: Boolean = false) = TaskConfig("测试视频", count, seconds, seconds, like, 100)

    private class FakePort : AutomationPort {
        var available: SafetyCheck = SafetyCheck.Ready
        var safe: SafetyCheck = SafetyCheck.Ready
        var foreground: TargetApp? = target
        val swipeTargets = mutableListOf<TargetApp>()
        val likeTargets = mutableListOf<TargetApp>()
        var swipeRequests = 0
        var likes = 0
        var likeOutcome: GestureOutcome = GestureOutcome.Completed
        var delayedSwipe: CompletableDeferred<GestureOutcome>? = null
        var ignoreSwipeCancellation = false
        var delayedLike: CompletableDeferred<GestureOutcome>? = null
        var ignoreLikeCancellation = false
        var inspectFailure: RuntimeException? = null
        var inspections = 0
        val outcomes = ArrayDeque<GestureOutcome>()
        override fun availability() = available
        override fun foregroundTarget(): TargetApp? = foreground
        override fun inspect(target: TargetApp): SafetyCheck {
            inspections++
            inspectFailure?.let { throw it }
            if (available is SafetyCheck.Blocked) return available
            val foregroundCheck = checkForeground(target.packageName, foreground?.packageName)
            if (foregroundCheck is SafetyCheck.Blocked) return foregroundCheck
            return safe
        }
        override suspend fun swipe(target: TargetApp): GestureOutcome {
            swipeRequests++
            swipeTargets += target
            val pending = delayedSwipe
            delayedSwipe = null
            if (pending != null) {
                return if (ignoreSwipeCancellation) withContext(NonCancellable) { pending.await() } else pending.await()
            }
            return outcomes.removeFirstOrNull() ?: GestureOutcome.Completed
        }
        override suspend fun doubleTap(target: TargetApp): GestureOutcome {
            likes++
            likeTargets += target
            val pending = delayedLike
            delayedLike = null
            if (pending != null) {
                return if (ignoreLikeCancellation) withContext(NonCancellable) { pending.await() } else pending.await()
            }
            return likeOutcome
        }
    }

    private fun TestScope.engine(port: FakePort, random: Random = Random.Default): AutomationEngine {
        var ids = 0
        return AutomationEngine(
            port, backgroundScope, StandardTestDispatcher(testScheduler),
            MonotonicClock { testScheduler.currentTime },
            random = random,
            wallTime = { testScheduler.currentTime },
            newId = { "run-" + ++ids },
        )
    }

    @Test
    fun startupWaitsTenSecondsAndStopsAtExactCount() = runTest {
        val port = FakePort()
        val engine = engine(port)
        assertEquals(CommandResult.Accepted, engine.start(config(), target) { Result.success(Unit) })
        runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(0, port.swipeRequests)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, port.swipeRequests)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, port.swipeRequests)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, port.swipeRequests)
        assertEquals(2, requireNotNull(engine.state.value.session).completed)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        assertTrue(engine.state.value.overlayVisible)
        assertTrue(engine.state.value.hasOverlayContent)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(2, port.swipeRequests)
    }

    @Test
    fun anUnexpectedFailureDoesNotCopyExceptionContentsIntoLogs() = runTest {
        val port = FakePort().apply { inspectFailure = IllegalStateException("PRIVATE_MARKER") }
        val engine = engine(port)
        engine.start(config(), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        val message = requireNotNull(engine.state.value.latestLog).message
        assertTrue(message.contains("IllegalStateException"))
        assertFalse(message.contains("PRIVATE_MARKER"))
        assertEquals(0, port.swipeRequests)
    }

    @Test
    fun pauseFreezesRemainingWait() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(13_000); runCurrent()
        engine.pause()
        assertEquals(7_000L, requireNotNull(engine.state.value.session).waitRemainingMs)
        advanceTimeBy(80_000); runCurrent()
        assertEquals(0, port.swipeRequests)
        engine.resume(); runCurrent()
        advanceTimeBy(6_999); runCurrent()
        assertEquals(0, port.swipeRequests)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun stopCancelsStartupAndIsIdempotent() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        engine.stop()
        val stopped = engine.state.value
        assertNull(stopped.session)
        assertNull(stopped.latestLog)
        assertNull(stopped.pauseReason)
        assertFalse(stopped.overlayVisible)
        engine.stop()
        advanceTimeBy(100_000); runCurrent()
        assertEquals(stopped, engine.state.value)
        assertEquals(0, port.swipeRequests)
        assertFalse(engine.state.value.locked)
    }

    @Test
    fun lateCompletionCannotMutateNewTask() = runTest {
        val port = FakePort()
        val oldCompletion = CompletableDeferred<GestureOutcome>()
        port.delayedSwipe = oldCompletion
        port.ignoreSwipeCancellation = true
        val engine = engine(port)
        engine.start(config(10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(1, port.swipeRequests)
        engine.stop()
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent()
        val newId = requireNotNull(engine.state.value.session).id
        oldCompletion.complete(GestureOutcome.Completed)
        runCurrent()
        assertEquals(newId, requireNotNull(engine.state.value.session).id)
        assertEquals(0, requireNotNull(engine.state.value.session).completed)
        advanceTimeBy(11_000); runCurrent()
        assertEquals(1, requireNotNull(engine.state.value.session).completed)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun rejectedGesturesRetryFinitelyWithoutCounting() = runTest {
        val port = FakePort()
        repeat(3) { port.outcomes.add(GestureOutcome.Rejected) }
        val engine = engine(port)
        engine.start(config(), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        assertEquals(3, port.swipeRequests)
        assertEquals(0, requireNotNull(engine.state.value.session).completed)
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
    }


    @Test
    fun doubleTapSettingControlsWhetherGesturesAreSent() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 1, false), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(0, port.likes)
        assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
        engine.start(config(1, 1, true), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(1, port.likes)
        assertEquals(1, requireNotNull(engine.state.value.session).likeCount)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun likeCountSurvivesPauseAndCompletionAndResetsForTheNextTask() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(2, 2, true), target) { Result.success(Unit) }
        assertEquals(0L, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(0, requireNotNull(engine.state.value.session).completed)
        assertEquals(1, requireNotNull(engine.state.value.session).likeCount)
        assertEquals(11_000L, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        engine.pause()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, requireNotNull(engine.state.value.session).likeCount)
        engine.showNotice("检查暂停状态")
        assertEquals(41_000L, requireNotNull(engine.state.value.latestLog).timeMillis)
        assertEquals(11_000L, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        engine.resume(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        assertEquals(2, requireNotNull(engine.state.value.session).completed)
        assertEquals(2, requireNotNull(engine.state.value.session).likeCount)
        assertEquals(43_000L, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        assertEquals(44_000L, requireNotNull(engine.state.value.latestLog).timeMillis)
        assertTrue(engine.state.value.hasOverlayContent)
        engine.stop()
        assertNull(engine.state.value.session)
        engine.start(config(1, 1, false), target) { Result.success(Unit) }
        assertEquals(44_000L, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
        assertEquals(2, port.likes)
        engine.stop()
    }

    @Test
    fun lateDoubleTapCompletionCannotCountAfterStopOrInAnotherTask() = runTest {
        for (startAgain in listOf(false, true)) {
            val completion = CompletableDeferred<GestureOutcome>()
            val port = FakePort().apply { delayedLike = completion; ignoreLikeCancellation = true }
            val engine = engine(port)
            engine.start(config(1, 2, true), target) { Result.success(Unit) }
            runCurrent(); advanceTimeBy(11_000); runCurrent()
            assertEquals(1, port.likes)
            assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
            engine.stop()
            if (startAgain) engine.start(config(1, 2, true), target) { Result.success(Unit) }
            runCurrent()
            val before = engine.state.value
            completion.complete(GestureOutcome.Completed); runCurrent()
            assertEquals(before, engine.state.value)
            if (startAgain) {
                advanceTimeBy(12_000); runCurrent()
                assertEquals(1, requireNotNull(engine.state.value.session).likeCount)
                assertEquals(RunStatus.COMPLETED, engine.state.value.status)
            } else {
                assertNull(engine.state.value.session)
                assertFalse(engine.state.value.overlayVisible)
            }
            engine.stop()
        }
    }
    @Test
    fun returningToTheTargetAutomaticallyResumesTheRemainingViewingTime() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
        engine.environmentChanged()
        advanceTimeBy(50_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
        assertEquals(8_000L, requireNotNull(engine.state.value.session).waitRemainingMs)
        port.safe = SafetyCheck.Blocked(BlockKind.GEOMETRY, "窗口尺寸尚未稳定")
        engine.environmentChanged(); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        port.safe = SafetyCheck.Ready
        engine.environmentChanged(); runCurrent()
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        advanceTimeBy(7_999); runCurrent()
        assertEquals(0, port.swipeRequests)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun continueUsesTheForegroundAppWithoutLaunchingOrChangingSavedSettings() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
        engine.environmentChanged()
        val other = TargetApp("other.video", "other.video/.Main", "其他软件")
        port.foreground = other
        port.safe = SafetyCheck.Ready
        assertEquals(CommandResult.Accepted, engine.resume())
        runCurrent(); advanceTimeBy(8_000); runCurrent()
        assertEquals(1, port.swipeRequests)
        assertEquals(listOf(other), port.swipeTargets)
        assertEquals(target, requireNotNull(engine.state.value.session).target)
        assertEquals("测试视频", requireNotNull(engine.state.value.session).config.appName)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun foregroundContinuationAppliesToLikesAndStillPausesOnTheNextAppSwitch() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 4, true), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        val other = TargetApp("other.video", "other.video/.Main", "其他软件")
        port.foreground = other
        engine.environmentChanged()
        assertEquals(CommandResult.Accepted, engine.resume())
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(other), port.likeTargets)
        port.foreground = target
        engine.environmentChanged()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
        port.foreground = other
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(other), port.swipeTargets)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }


    @Test
    fun unreadableWindowContentDoesNotPauseAnActiveTask() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10, true), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.foreground = null
        engine.environmentChanged()
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1, port.likes)
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }
    @Test
    fun noForegroundAppKeepsTheTaskPausedAndShowsTheFloatingControls() = runTest {
        val port = FakePort().apply { foreground = null }
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent()
        engine.pause()
        assertTrue(engine.resume() is CommandResult.Rejected)
        assertTrue(engine.state.value.overlayVisible)
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
    }

    @Test
    fun manualPauseOverridesThePendingAutomaticResume() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
        engine.environmentChanged()
        engine.pause()
        port.safe = SafetyCheck.Ready
        engine.environmentChanged()
        advanceTimeBy(50_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
        assertEquals(CommandResult.Accepted, engine.resume())
        runCurrent(); advanceTimeBy(8_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun stoppingWhileAwayCannotResumeWhenTheTargetReturns() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
        engine.environmentChanged()
        engine.stop()
        port.safe = SafetyCheck.Ready
        engine.environmentChanged()
        advanceTimeBy(50_000); runCurrent()
        assertEquals(RunStatus.STOPPED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
    }

    @Test
    fun permissionScreenAndServiceBlocksCancelAutomaticRecovery() = runTest {
        for (kind in listOf(BlockKind.PERMISSION, BlockKind.SCREEN, BlockKind.SERVICE)) {
            val port = FakePort()
            val engine = engine(port)
            engine.start(config(1, 10), target) { Result.success(Unit) }
            runCurrent(); advanceTimeBy(12_000); runCurrent()
            port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
            engine.environmentChanged()
            val blocked = SafetyCheck.Blocked(kind, "需要手动处理")
            port.available = blocked
            engine.environmentChanged()
            port.safe = SafetyCheck.Ready
            port.available = SafetyCheck.Ready
            engine.environmentChanged()
            advanceTimeBy(50_000); runCurrent()
            assertEquals(RunStatus.PAUSED, engine.state.value.status, kind.name)
            assertEquals(0, port.swipeRequests, kind.name)
            engine.stop()
        }
    }

    @Test
    fun foregroundLossDetectedAtSwipeDispatchCanAutomaticallyRecover() = runTest {
        val port = FakePort().apply {
            outcomes.add(GestureOutcome.Blocked("目标应用已离开前台", BlockKind.FOREGROUND))
        }
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, requireNotNull(engine.state.value.session).completed)
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, port.swipeRequests)
        assertEquals(1, requireNotNull(engine.state.value.session).completed)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun foregroundLossAtLikeDispatchResumesWithoutRepeatingTheDoubleTap() = runTest {
        val port = FakePort().apply { likeOutcome = GestureOutcome.Blocked("目标应用已离开前台", BlockKind.FOREGROUND) }
        val engine = engine(port)
        engine.start(config(1, 2, true), target) { Result.success(Unit) }
        val initialLikeTime = requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(1, port.likes)
        assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
        assertEquals(initialLikeTime, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, port.likes)
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }


    @Test
    fun interruptedSwipesContinueInTheSameAppAndWaitWhenTheAppChanges() = runTest {
        for (lostForeground in listOf(false, true)) {
            val completion = CompletableDeferred<GestureOutcome>()
            val port = FakePort().apply { delayedSwipe = completion }
            val engine = engine(port)
            engine.start(config(1), target) { Result.success(Unit) }
            runCurrent(); advanceTimeBy(11_000); runCurrent()
            if (lostForeground) port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
            completion.complete(GestureOutcome.Interrupted)
            runCurrent()
            assertEquals(if (lostForeground) RunStatus.PAUSED else RunStatus.RUNNING, engine.state.value.status)
            assertEquals(0, requireNotNull(engine.state.value.session).completed)
            port.safe = SafetyCheck.Ready
            engine.environmentChanged(); runCurrent()
            advanceTimeBy(999); runCurrent()
            assertEquals(1, port.swipeRequests)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, port.swipeRequests)
            assertEquals(RunStatus.COMPLETED, engine.state.value.status)
            engine.stop()
        }
    }

    @Test
    fun aRejectedOrInterruptedDoubleTapIsNotCountedPausedOrReplayed() = runTest {
        for (outcome in listOf(GestureOutcome.Rejected, GestureOutcome.Interrupted)) {
            val port = FakePort().apply { likeOutcome = outcome }
            val engine = engine(port)
            engine.start(config(1, 2, true), target) { Result.success(Unit) }
            val initialLikeTime = requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis
            runCurrent(); advanceTimeBy(11_000); runCurrent()
            assertEquals(1, port.likes)
            assertEquals(RunStatus.RUNNING, engine.state.value.status)
            assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
            assertEquals(initialLikeTime, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, port.likes)
            assertEquals(1, port.swipeRequests)
            assertEquals(0, requireNotNull(engine.state.value.session).likeCount)
            assertEquals(RunStatus.COMPLETED, engine.state.value.status)
            assertEquals(initialLikeTime, requireNotNull(engine.state.value.session).likeCountUpdatedAtMillis)
            engine.stop()
        }
    }
    @Test
    fun manualPauseDuringStartupPreservesTheRemainingStartupDelay() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        engine.pause()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(7_000L, requireNotNull(engine.state.value.session).waitRemainingMs)
        engine.resume(); runCurrent()
        advanceTimeBy(7_000); runCurrent()
        assertEquals(0, port.swipeRequests)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun aNewEngineRequiresANewTaskAfterAnInterruptedRun() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(10, 1, true), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        engine.pause()
        assertEquals(1, requireNotNull(engine.state.value.session).completed)
        assertEquals(1, requireNotNull(engine.state.value.session).likeCount)
        val restarted = engine(port)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.IDLE, restarted.state.value.status)
        assertNull(restarted.state.value.session)
        assertFalse(restarted.state.value.overlayVisible)
        assertTrue(restarted.resume() is CommandResult.Rejected)
        restarted.start(config(1, 1, true), target) { Result.success(Unit) }
        assertEquals(0, requireNotNull(restarted.state.value.session).completed)
        assertEquals(0, requireNotNull(restarted.state.value.session).likeCount)
        runCurrent()
        advanceTimeBy(11_000); runCurrent()
        assertEquals(1, requireNotNull(restarted.state.value.session).completed)
        engine.stop()
        restarted.stop()
    }

    @Test
    fun stopDiscardsTheResultAndANewTaskStartsFromZero() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(3), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        val previous = requireNotNull(engine.state.value.session)
        assertEquals(1, previous.completed)
        engine.stop()
        assertNull(engine.state.value.session)
        assertFalse(engine.state.value.overlayVisible)
        assertEquals(CommandResult.Accepted, engine.start(config(3), target) { Result.success(Unit) })
        runCurrent()
        val fresh = requireNotNull(engine.state.value.session)
        assertNotEquals(previous.id, fresh.id)
        assertEquals(0, fresh.completed)
        assertEquals(previous.config, fresh.config)
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        advanceTimeBy(11_000); runCurrent()
        assertEquals(1, requireNotNull(engine.state.value.session).completed)
        engine.stop()
    }

    @Test
    fun completedTaskCanStartAgainAndADuplicateStartDoesNotPauseIt() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        assertTrue(engine.state.value.overlayVisible)
        assertEquals(CommandResult.Accepted, engine.start(config(1), target) { Result.success(Unit) })
        assertTrue(engine.start(config(1), target) { Result.success(Unit) } is CommandResult.Rejected)
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(2, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun startupIsExactlyTenSecondsWithoutReadinessPollingOrExtraSettlingDelay() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "应用切换中")
        engine.environmentChanged()
        advanceTimeBy(5_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.GEOMETRY, "屏幕尺寸暂不可用")
        engine.environmentChanged()
        advanceTimeBy(4_999); runCurrent()
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        assertEquals(RunStage.STARTUP, requireNotNull(engine.state.value.session).stage)
        assertEquals(0, port.inspections)
        assertEquals(0, port.swipeRequests)
        port.safe = SafetyCheck.Ready
        advanceTimeBy(1); runCurrent()
        assertEquals(RunStage.WAIT_AFTER_LIKE, requireNotNull(engine.state.value.session).stage)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        assertEquals(1, port.swipeRequests)
    }


    @Test
    fun invalidScreenGeometryIsCheckedAfterTheFixedStartupWait() = runTest {
        val port = FakePort().apply { safe = SafetyCheck.Blocked(BlockKind.GEOMETRY, "屏幕尺寸暂不可用") }
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent()
        engine.environmentChanged()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(RunStatus.RUNNING, engine.state.value.status)
        assertEquals(0, port.inspections)
        advanceTimeBy(1); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
        port.safe = SafetyCheck.Ready
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        engine.stop()
    }
    @Test
    fun leavingDuringStartupIsCheckedAfterTenSecondsAndReturningDoesNotRestartTheDelay() = runTest {
        val port = FakePort().apply { safe = SafetyCheck.Blocked(BlockKind.FOREGROUND, "应用尚未进入前台") }
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0L, requireNotNull(engine.state.value.session).waitRemainingMs)
        assertEquals(0, port.swipeRequests)
        port.safe = SafetyCheck.Ready
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
    }

    @Test
    fun permissionScreenAndServiceLossInterruptTheFixedStartupWait() = runTest {
        for (kind in listOf(BlockKind.PERMISSION, BlockKind.SCREEN, BlockKind.SERVICE)) {
            val port = FakePort()
            val engine = engine(port)
            engine.start(config(1), target) { Result.success(Unit) }
            runCurrent(); advanceTimeBy(2_000); runCurrent()
            port.available = SafetyCheck.Blocked(kind, "运行条件已失效")
            engine.environmentChanged()
            assertEquals(RunStatus.PAUSED, engine.state.value.status, kind.name)
            assertEquals(8_000L, requireNotNull(engine.state.value.session).waitRemainingMs)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(0, port.swipeRequests)
            engine.stop()
        }
    }

    @Test
    fun aLateOldSwipeCannotReopenTheWindowAfterStop() = runTest {
        val completion = CompletableDeferred<GestureOutcome>()
        val port = FakePort().apply { delayedSwipe = completion; ignoreSwipeCancellation = true }
        val engine = engine(port)
        engine.start(config(2), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        engine.stop()
        completion.complete(GestureOutcome.Completed); runCurrent()
        assertNull(engine.state.value.session)
        assertFalse(engine.state.value.overlayVisible)
        advanceTimeBy(11_000); runCurrent()
        assertNull(engine.state.value.session)
        assertEquals(RunStatus.STOPPED, engine.state.value.status)
    }


    @Test
    fun screenGeometryRecoveryPreservesTheRemainingWait() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.safe = SafetyCheck.Blocked(BlockKind.GEOMETRY, "屏幕尺寸暂不可用")
        engine.environmentChanged()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertTrue(engine.waitingForAutoResume)
        assertEquals(8_000L, requireNotNull(engine.state.value.session).waitRemainingMs)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, port.swipeRequests)
        port.safe = SafetyCheck.Ready
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        assertTrue(engine.state.value.overlayVisible)
        assertFalse(engine.waitingForAutoResume)
    }

    @Test
    fun unknownForegroundCannotResumeAPreviouslyPausedTask() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(12_000); runCurrent()
        port.foreground = TargetApp("other.app", "other.app/.Main", "其他软件")
        engine.environmentChanged()
        assertTrue(engine.waitingForAutoResume)
        port.foreground = null
        engine.environmentChanged()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(RunStatus.PAUSED, engine.state.value.status)
        assertEquals(0, port.swipeRequests)
        port.foreground = target
        engine.environmentChanged(); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        engine.stop()
    }
    @Test
    fun intervalSamplingUsesMillisecondsInsideTheConfiguredSecondRange() = runTest {
        val port = FakePort()
        val engine = engine(port, Random(42))
        val configuration = TaskConfig("测试应用", 20, 5, 10, false)
        engine.start(configuration, target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        val intervals = mutableListOf<Long>()
        repeat(20) {
            val remaining = requireNotNull(engine.state.value.session).waitRemainingMs
            intervals += requireNotNull(engine.state.value.session).dwellMs
            advanceTimeBy(remaining); runCurrent()
        }
        assertTrue(intervals.all { it in 5_000L..10_000L })
        assertTrue(intervals.any { it % 1_000L != 0L })
        assertEquals(20, port.swipeRequests)
        assertEquals(RunStatus.COMPLETED, engine.state.value.status)
        engine.stop()
    }

    @Test
    fun completingKeepsOneResultVisibleUntilStopped() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent(); advanceTimeBy(11_000); runCurrent()
        val completed = engine.state.value
        assertTrue(completed.hasOverlayContent)
        assertEquals(1, requireNotNull(completed.session).completed)
        assertEquals("已完成 1 次", completed.latestLog?.message)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(completed, engine.state.value)
        assertEquals(1, port.swipeRequests)
        engine.stop()
        assertFalse(engine.state.value.hasOverlayContent)
        assertNull(engine.state.value.session)
        assertNull(engine.state.value.latestLog)
    }

    @Test
    fun statusUpdatesReplaceTheCurrentMessageAndDoNotChangeTaskProgress() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.start(config(1, 10), target) { Result.success(Unit) }
        runCurrent()
        val session = engine.state.value.session
        repeat(1_000) { engine.showNotice("状态 $it：" + "内容".repeat(300)) }
        val last = requireNotNull(engine.state.value.latestLog)
        assertEquals(LogEntry.MAX_MESSAGE_LENGTH, last.message.length)
        assertTrue(last.message.startsWith("状态 999："))
        assertEquals(session, engine.state.value.session)
        engine.stop()
    }

    @Test
    fun hiddenAndRepeatedMessagesDoNotCreateExtraStatusUpdates() = runTest {
        val port = FakePort()
        val engine = engine(port)
        engine.showNotice("尚未显示悬浮窗")
        assertNull(engine.state.value.latestLog)
        engine.start(config(1), target) { Result.success(Unit) }
        runCurrent()
        engine.showNotice("当前状态")
        val before = engine.state.value.latestLog
        advanceTimeBy(10); runCurrent()
        engine.showNotice("当前状态")
        assertSame(before, engine.state.value.latestLog)
        engine.stop()
    }
}
