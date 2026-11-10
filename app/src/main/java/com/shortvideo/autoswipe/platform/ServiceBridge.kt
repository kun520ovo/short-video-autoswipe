package com.shortvideo.autoswipe.platform

import com.shortvideo.autoswipe.domain.*
import com.shortvideo.autoswipe.model.TargetApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface AccessibilityBackend : AutomationPort {
    fun refreshOverlay()
}

class ServiceBridge : AutomationPort {
    private var backend: AccessibilityBackend? = null
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()

    fun attach(value: AccessibilityBackend) {
        backend = value
        mutableError.value = null
        mutableConnected.value = true
        changed()
    }

    fun detach(value: AccessibilityBackend) {
        if (backend !== value) return
        backend = null
        mutableConnected.value = false
        changed()
    }

    fun changed() { mutableChanges.tryEmit(Unit) }
    fun refresh() { backend?.refreshOverlay(); changed() }

    fun failed(value: AccessibilityBackend, message: String) {
        if (backend != null && backend !== value) return
        backend = null
        mutableConnected.value = false
        mutableError.value = message
        changed()
    }

    private val missing = SafetyCheck.Blocked(BlockKind.SERVICE, "无障碍服务未连接，请重新开启服务")
    private val unavailable = SafetyCheck.Blocked(BlockKind.SERVICE, "暂时无法读取运行状态，请稍后重试或重新开启无障碍服务")

    private inline fun <T> query(fallback: T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        fallback
    }

    override fun availability(): SafetyCheck = query(unavailable) { backend?.availability() ?: missing }
    override fun foregroundTarget(): TargetApp? = query(null) { backend?.foregroundTarget() }
    override fun inspect(target: TargetApp): SafetyCheck = query(unavailable) { backend?.inspect(target) ?: missing }
    override suspend fun swipe(target: TargetApp): GestureOutcome = backend?.swipe(target) ?: GestureOutcome.Blocked(missing.reason)
    override suspend fun doubleTap(target: TargetApp): GestureOutcome = backend?.doubleTap(target) ?: GestureOutcome.Blocked(missing.reason)
}
