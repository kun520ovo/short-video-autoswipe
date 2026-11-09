package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.TargetApp
import kotlinx.coroutines.withTimeoutOrNull

enum class BlockKind { SERVICE, PERMISSION, SCREEN, FOREGROUND, GEOMETRY }

sealed interface SafetyCheck {
    data object Ready : SafetyCheck
    data class Blocked(val kind: BlockKind, val reason: String) : SafetyCheck
}

internal fun SafetyCheck.Blocked.canResumeAutomatically(): Boolean =
    kind == BlockKind.FOREGROUND || kind == BlockKind.GEOMETRY

sealed interface GestureOutcome {
    data object Completed : GestureOutcome
    data object Rejected : GestureOutcome
    data object Interrupted : GestureOutcome
    data class Blocked(val reason: String, val kind: BlockKind? = null) : GestureOutcome
}

/** Bound only the dispatched gesture; time spent holding the overlay is excluded. */
internal suspend fun awaitGestureResult(block: suspend () -> GestureOutcome): GestureOutcome =
    withTimeoutOrNull(2_500) { block() }
        ?: GestureOutcome.Blocked("手势结果超时，请重新开启无障碍服务后继续")

interface AutomationPort {
    fun availability(): SafetyCheck
    fun foregroundTarget(): TargetApp?
    fun inspect(target: TargetApp): SafetyCheck
    suspend fun swipe(target: TargetApp): GestureOutcome
    suspend fun doubleTap(target: TargetApp): GestureOutcome
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val message: String) : CommandResult
}
