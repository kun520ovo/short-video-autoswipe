package com.shortvideo.autoswipe.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.shortvideo.autoswipe.domain.*
import com.shortvideo.autoswipe.model.TargetApp
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidGestureExecutor(
    private val service: AccessibilityService,
    private val inspector: ScreenInspector,
    private val overlayBounds: () -> PixelRect?,
    private val awaitOverlayIdle: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var inFlight: CompletableDeferred<Unit>? = null
    private var continuation: CancellableContinuation<GestureOutcome>? = null
    private var closed = false

    suspend fun swipe(target: TargetApp): GestureOutcome = perform(target, false)
    suspend fun doubleTap(target: TargetApp): GestureOutcome = perform(target, true)

    private suspend fun perform(target: TargetApp, like: Boolean): GestureOutcome = mutex.withLock {
        if (closed) return@withLock GestureOutcome.Blocked("无障碍服务已断开")
        val previous = inFlight
        if (previous != null && withTimeoutOrNull(1_200) { previous.await(); true } != true) {
            return@withLock GestureOutcome.Blocked(GESTURE_RECOVERY_GUIDE)
        }
        awaitOverlayIdle()
        currentCoroutineContext().ensureActive()
        if (closed) return@withLock GestureOutcome.Blocked("无障碍服务已断开")
        val floating = overlayBounds()
            ?: return@withLock GestureOutcome.Blocked("悬浮窗暂时不可用，请检查后继续")
        val snapshot = inspector.inspect(target)
        val check = snapshot.safety
        if (check is SafetyCheck.Blocked) return@withLock GestureOutcome.Blocked(check.reason, check.kind)
        val content = snapshot.content ?: return@withLock GestureOutcome.Blocked("无法确定操作区域")
        val density = service.resources.displayMetrics.density
        val obstacles = listOf(floating.expanded(10 * density))
        val description = if (like) {
            val point = GesturePlanner.doubleTap(content, obstacles, 8 * density)
                ?: return@withLock GestureOutcome.Blocked(
                    if (GesturePlanner.doubleTap(content, emptyList(), 8 * density) != null) {
                        "悬浮窗挡住双击位置，请移动悬浮窗"
                    } else "屏幕没有足够的双击空间",
                )
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(point.x, point.y) }, 0, 45))
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(point.x, point.y) }, 150, 45))
                .build()
        } else {
            val swipe = GesturePlanner.swipe(content, obstacles, 8 * density)
                ?: return@withLock GestureOutcome.Blocked(
                    if (GesturePlanner.swipe(content, emptyList(), 8 * density) != null) {
                        "悬浮窗挡住滑动路径，请移动悬浮窗"
                    } else "屏幕没有足够的滑动空间",
                )
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(swipe.start.x, swipe.start.y); lineTo(swipe.end.x, swipe.end.y) },
                    0, swipe.durationMs,
                ),
            ).build()
        }
        currentCoroutineContext().ensureActive()
        awaitGestureResult { dispatch(description) }
    }

    private suspend fun dispatch(description: GestureDescription): GestureOutcome =
        suspendCancellableCoroutine { waiting ->
            continuation = waiting
            val settled = CompletableDeferred<Unit>()
            inFlight = settled
            fun finish(outcome: GestureOutcome) {
                settled.complete(Unit)
                if (inFlight === settled) inFlight = null
                if (continuation === waiting) continuation = null
                if (waiting.isActive) waiting.resume(outcome)
            }
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = finish(GestureOutcome.Completed)
                override fun onCancelled(gestureDescription: GestureDescription) = finish(GestureOutcome.Interrupted)
            }
            try {
                if (!service.dispatchGesture(description, callback, handler)) finish(GestureOutcome.Rejected)
            } catch (_: RuntimeException) {
                finish(GestureOutcome.Rejected)
            }
            // The system has no general cancel-current-gesture API. Keep its completion
            // latch until the callback, even when this waiting coroutine is cancelled.
            waiting.invokeOnCancellation { if (continuation === waiting) continuation = null }
        }

    fun close() {
        closed = true
        continuation?.let { if (it.isActive) it.resume(GestureOutcome.Interrupted) }
        continuation = null
        inFlight?.complete(Unit)
        inFlight = null
    }
}
