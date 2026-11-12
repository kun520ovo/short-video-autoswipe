package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.*
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All commands and completions are serialized on [dispatcher].
 * Cancellation invalidates the generation before cancelling the worker.
 */
class AutomationEngine(
    private val port: AutomationPort,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: MonotonicClock,
    private val random: Random = Random.Default,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = mutableState.asStateFlow()
    private var worker: Job? = null
    private var generation = 0L
    private var waitTicket: WaitTicket? = null
    // App/window transitions may resume; this intent is never persisted across processes.
    private var resumeWhenReady = false
    // Manual Continue can control another app without changing the saved launch settings.
    private var resumedTarget: TargetApp? = null
    val waitingForAutoResume: Boolean
        get() = state.value.status == RunStatus.PAUSED && resumeWhenReady

    private data class WaitTicket(
        val sessionId: String,
        val stage: RunStage,
        val start: Long,
        val duration: Long,
    )

    suspend fun start(
        config: TaskConfig,
        target: TargetApp,
        launchTarget: () -> Result<Unit>,
    ): CommandResult = withContext(dispatcher) {
        if (state.value.locked) return@withContext CommandResult.Rejected("已有任务，请先停止")
        if (!config.isValid()) return@withContext CommandResult.Rejected("请检查任务参数")
        val readiness = port.availability()
        if (readiness is SafetyCheck.Blocked) return@withContext CommandResult.Rejected(readiness.reason)
        val launched = runCatching { launchTarget().getOrThrow() }
        if (launched.isFailure) return@withContext CommandResult.Rejected("无法打开目标应用，请重新选择")
        cancelWorker()
        resumedTarget = null
        mutableState.value = RunState(
            status = RunStatus.RUNNING,
            session = RunSession(
                newId(), config.copy(appName = target.label), target,
                likeCountUpdatedAtMillis = wallTime(),
            ),
            overlayVisible = true,
        )
        log("正在打开 " + target.label)
        launchWorker()
        CommandResult.Accepted
    }

    suspend fun pause(reason: String = "任务已暂停") = withContext(dispatcher) {
        resumeWhenReady = false
        if (state.value.running) pauseInternal(reason)
        else if (state.value.status == RunStatus.PAUSED && state.value.pauseReason != reason) {
            mutableState.value = state.value.copy(pauseReason = reason)
            log(reason)
        }
    }

    suspend fun resume(): CommandResult = withContext(dispatcher) {
        val current = state.value
        if (current.status != RunStatus.PAUSED) return@withContext CommandResult.Rejected("当前没有可继续的任务")
        if (current.session == null) return@withContext CommandResult.Rejected("任务数据不可用")
        val readiness = port.availability()
        if (readiness is SafetyCheck.Blocked) return@withContext CommandResult.Rejected(readiness.reason)
        val foreground = port.foregroundTarget()
        if (foreground == null) {
            val message = "请切到要操作的应用，再点击悬浮窗继续"
            mutableState.value = current.copy(overlayVisible = true)
            log(message)
            return@withContext CommandResult.Rejected(message)
        }
        val check = port.inspect(foreground)
        if (check is SafetyCheck.Blocked &&
            (check.kind != BlockKind.GEOMETRY || current.session.stage != RunStage.STARTUP)) {
            return@withContext CommandResult.Rejected(check.reason)
        }
        resumedTarget = foreground
        continueSession(current)
        CommandResult.Accepted
    }

    suspend fun stop() = withContext(dispatcher) {
        if (state.value.session == null && !state.value.overlayVisible) return@withContext
        cancelWorker()
        resumedTarget = null
        mutableState.value = RunState(status = RunStatus.STOPPED)
    }

    suspend fun showNotice(message: String) = withContext(dispatcher) { log(message) }

    suspend fun environmentChanged() = withContext(dispatcher) {
        val waitingForTarget = waitingForAutoResume
        if (!state.value.running && !waitingForTarget) return@withContext
        val readiness = port.availability()
        if (readiness is SafetyCheck.Blocked) {
            pause(readiness.reason)
            return@withContext
        }
        if (waitingForTarget) {
            // Unknown window metadata must not restart an already paused task.
            if (port.foregroundTarget()?.packageName != activeTarget().packageName) return@withContext
            when (val check = port.inspect(activeTarget())) {
                // Automatic recovery does not relaunch the app.
                SafetyCheck.Ready -> continueSession(state.value)
                is SafetyCheck.Blocked -> if (!check.canResumeAutomatically()) pause(check.reason)
            }
            return@withContext
        }
        // A fixed startup delay absorbs launch animations. Foreground checks run when
        // the delay ends; permission loss, screen-off and manual commands still interrupt it.
        if (session().stage == RunStage.STARTUP) return@withContext
        when (val check = port.inspect(activeTarget())) {
            SafetyCheck.Ready -> Unit
            is SafetyCheck.Blocked -> pauseForBlock(check.reason, check.kind)
        }
    }

    private fun session(): RunSession = requireNotNull(state.value.session)
    private fun activeTarget(): TargetApp = resumedTarget ?: session().target
    private fun updateSession(transform: (RunSession) -> RunSession) {
        mutableState.value = state.value.copy(session = transform(session()))
    }
    private fun current(token: Long): Boolean = token == generation && state.value.running

    private fun log(message: String) {
        if (!state.value.overlayVisible) return
        val text = message.take(LogEntry.MAX_MESSAGE_LENGTH)
        if (state.value.latestLog?.message == text) return
        val entry = LogEntry(wallTime(), text)
        mutableState.value = state.value.copy(latestLog = entry)
    }

    private fun settleWait() {
        val ticket = waitTicket ?: return
        val value = state.value.session
        if (value != null && value.id == ticket.sessionId && value.stage == ticket.stage) {
            val spent = (clock.nowMillis() - ticket.start).coerceIn(0L, ticket.duration)
            updateSession { it.copy(waitRemainingMs = ticket.duration - spent) }
        }
        waitTicket = null
    }

    private fun cancelWorker() {
        settleWait()
        generation++
        worker?.cancel()
        worker = null
        resumeWhenReady = false
    }

    private fun continueSession(current: RunState) {
        cancelWorker()
        mutableState.value = current.copy(status = RunStatus.RUNNING, pauseReason = null, overlayVisible = true)
        log(if (current.session?.stage == RunStage.STARTUP) "继续启动等待" else "继续运行：" + activeTarget().label)
        launchWorker()
    }

    private fun pauseInternal(reason: String, resumeOnReturn: Boolean = false) {
        if (!state.value.running) return
        cancelWorker()
        resumeWhenReady = resumeOnReturn
        mutableState.value = state.value.copy(status = RunStatus.PAUSED, pauseReason = reason)
        log(reason)
    }

    private fun pauseForBlock(reason: String, kind: BlockKind?) {
        val foregroundLost = kind == BlockKind.FOREGROUND
        val canRecover = foregroundLost || kind == BlockKind.GEOMETRY
        pauseInternal(if (foregroundLost) foregroundRecoveryMessage() else reason, resumeOnReturn = canRecover)
    }

    private fun foregroundRecoveryMessage(): String {
        val prefix = "等待"
        val suffix = "，切回后自动继续"
        val name = activeTarget().label
        val limit = LogEntry.MAX_MESSAGE_LENGTH - prefix.length - suffix.length
        val label = if (name.length <= limit) name else name.take(limit - 1).trimEnd { it.isHighSurrogate() } + "…"
        return prefix + label + suffix
    }

    private fun continueAfterInterruptedGesture(): Boolean = when (val check = port.inspect(activeTarget())) {
        SafetyCheck.Ready -> { log("手势被打断，稍后继续"); true }
        is SafetyCheck.Blocked -> { pauseForBlock(check.reason, check.kind); false }
    }

    private fun complete() {
        cancelWorker()
        resumedTarget = null
        mutableState.value = state.value.copy(status = RunStatus.COMPLETED, session = session().copy(waitRemainingMs = 0), pauseReason = null, overlayVisible = true)
        log("已完成 " + session().completed + " 次")
    }

    private fun launchWorker() {
        if (!state.value.running) return
        val token = generation
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                if (!current(token)) return@launch
                if (session().stage == RunStage.STARTUP) {
                    log(if (session().waitRemainingMs == 10_000L) "打开应用后先等待 10 秒" else "继续启动等待")
                }
                while (current(token)) {
                    when (session().stage) {
                        RunStage.STARTUP -> {
                            waitCurrentStage(token)
                            if (!current(token) || !requireSafe()) return@launch
                            log("开始自动上滑")
                            updateSession { it.copy(stage = RunStage.PREPARE_CYCLE) }
                        }
                        RunStage.PREPARE_CYCLE -> prepareCycle()
                        RunStage.WAIT_BEFORE_LIKE -> {
                            if (!requireSafe()) return@launch
                            waitCurrentStage(token)
                            if (!current(token)) return@launch
                            updateSession { it.copy(stage = RunStage.LIKE) }
                        }
                        RunStage.LIKE -> {
                            if (!requireSafe()) return@launch
                            // A partially dispatched double tap is not replayed after pause.
                            updateSession { it.copy(stage = RunStage.WAIT_AFTER_LIKE, waitRemainingMs = it.afterLikeMs) }
                            waitTicket = WaitTicket(session().id, session().stage, clock.nowMillis(), session().waitRemainingMs)
                            val outcome = port.doubleTap(activeTarget())
                            if (!current(token)) return@launch
                            settleWait()
                            when (outcome) {
                                GestureOutcome.Completed -> {
                                    updateSession { it.copy(likeCount = it.likeCount + 1, likeCountUpdatedAtMillis = wallTime()) }
                                    log("已执行双击")
                                }
                                GestureOutcome.Rejected -> log("本次双击未执行，已跳过")
                                GestureOutcome.Interrupted -> if (!continueAfterInterruptedGesture()) return@launch
                                is GestureOutcome.Blocked -> { pauseForBlock(outcome.reason, outcome.kind); return@launch }
                            }
                        }
                        RunStage.WAIT_AFTER_LIKE -> {
                            if (!requireSafe()) return@launch
                            waitCurrentStage(token)
                            if (!current(token)) return@launch
                            updateSession { it.copy(stage = RunStage.SWIPE) }
                        }
                        RunStage.SWIPE -> {
                            if (!requireSafe()) return@launch
                            // An interrupted swipe resumes with a fresh waiting interval.
                            updateSession { it.copy(stage = RunStage.PREPARE_CYCLE) }
                            val outcome = port.swipe(activeTarget())
                            if (!current(token)) return@launch
                            when (outcome) {
                                GestureOutcome.Completed -> {
                                    updateSession { it.copy(completed = it.completed + 1, retries = 0) }
                                    log("第 " + session().completed + " 次上滑完成")
                                    if (session().completed >= session().config.swipeCount) {
                                        complete()
                                        return@launch
                                    }
                                }
                                GestureOutcome.Rejected -> {
                                    if (session().retries >= 2) {
                                        pauseInternal("滑屏请求连续失败，已暂停")
                                        return@launch
                                    }
                                    updateSession { it.copy(stage = RunStage.SWIPE_RETRY_WAIT, waitRemainingMs = 500, retries = it.retries + 1) }
                                    log("滑屏请求未接收，准备重试")
                                }
                                GestureOutcome.Interrupted -> if (!continueAfterInterruptedGesture()) return@launch
                                is GestureOutcome.Blocked -> { pauseForBlock(outcome.reason, outcome.kind); return@launch }
                            }
                        }
                        RunStage.SWIPE_RETRY_WAIT -> {
                            waitCurrentStage(token)
                            if (!current(token)) return@launch
                            updateSession { it.copy(stage = RunStage.SWIPE) }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // The command that cancelled this worker owns the terminal state.
            } catch (error: Exception) {
                if (current(token)) pauseInternal("运行中断，请检查目标应用（" + error.javaClass.simpleName + "）")
            } finally {
                if (generation == token) {
                    waitTicket = null
                    worker = null
                }
            }
        }
        worker = job
        job.start()
    }

    private fun requireSafe(): Boolean = when (val check = port.inspect(activeTarget())) {
        SafetyCheck.Ready -> true
        is SafetyCheck.Blocked -> { pauseForBlock(check.reason, check.kind); false }
    }

    private fun prepareCycle() {
        val config = session().config
        val dwell = random.nextLong(config.minSeconds * 1000L, config.maxSeconds * 1000L + 1)
        val like = config.randomLike && random.nextInt(100) < config.likePercent
        updateSession {
            it.copy(
                stage = if (like) RunStage.WAIT_BEFORE_LIKE else RunStage.WAIT_AFTER_LIKE,
                waitRemainingMs = if (like) dwell / 2 else dwell,
                dwellMs = dwell,
                afterLikeMs = if (like) dwell - dwell / 2 else 0,
                retries = 0,
            )
        }
    }

    private suspend fun waitCurrentStage(token: Long) {
        val value = session()
        if (value.waitRemainingMs <= 0 || !current(token)) return
        val ticket = WaitTicket(value.id, value.stage, clock.nowMillis(), value.waitRemainingMs)
        waitTicket = ticket
        delay(ticket.duration)
        if (!current(token)) return
        if (waitTicket == ticket) {
            waitTicket = null
            updateSession { it.copy(waitRemainingMs = 0) }
        }
    }
}
