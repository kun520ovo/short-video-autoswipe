package com.shortvideo.autoswipe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.shortvideo.autoswipe.appContainer
import com.shortvideo.autoswipe.domain.*
import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.model.RunControlAction
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.overlay.OverlayWindow
import com.shortvideo.autoswipe.platform.AccessibilityBackend
import com.shortvideo.autoswipe.platform.ScreenGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SwipeAccessibilityService : AccessibilityService(), AccessibilityBackend {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connected = false
    private var receiverRegistered = false
    private var environmentJob: Job? = null
    private lateinit var inspector: ScreenInspector
    private lateinit var executor: AndroidGestureExecutor
    private lateinit var overlay: OverlayWindow
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                appContainer.scope.launch { appContainer.engine.pause("屏幕已熄灭，请解锁后继续") }
            }
            appContainer.bridge.changed()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            connectService()
        } catch (_: Exception) {
            shutdown()
            appContainer.bridge.failed(this, "服务未能启动，请重新开启无障碍服务")
        }
    }

    private fun connectService() {
        shutdown()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val container = appContainer
        val windowContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = requireNotNull(getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        val geometry = ScreenGeometry(windowContext)
        inspector = ScreenInspector(this, geometry, container.permissionChecker)
        overlay = OverlayWindow(
            context = windowContext,
            geometry = geometry,
            onAction = { action ->
                container.scope.launch {
                    when (action) {
                        RunControlAction.PAUSE -> container.engine.pause()
                        RunControlAction.RESUME -> {
                            val result = container.engine.resume()
                            if (result is CommandResult.Rejected && container.engine.state.value.status == RunStatus.PAUSED) {
                                container.engine.showNotice(result.message)
                            }
                        }
                        RunControlAction.STOP -> container.engine.stop()
                    }
                }
            },
            onFailure = { message -> container.scope.launch { container.engine.pause(message) } },
        )
        executor = AndroidGestureExecutor(this, inspector, overlay::currentBounds, overlay::awaitTouchRelease)
        connected = true
        container.bridge.attach(this)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        serviceScope.launch {
            container.engine.state.collect { overlay.update(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        inspector.record(event)
        // Coalesce bursts without postponing inspection indefinitely.
        if (environmentJob?.isActive == true) return
        environmentJob = serviceScope.launch {
            delay(150)
            appContainer.bridge.changed()
            refreshOverlay()
        }
    }

    override fun onInterrupt() {
        appContainer.scope.launch { appContainer.engine.pause("系统中断了服务，请点击继续") }
    }

    override fun onUnbind(intent: Intent): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (connected) { appContainer.bridge.changed(); refreshOverlay() }
    }

    override fun availability(): SafetyCheck =
        if (connected) inspector.availability() else SafetyCheck.Blocked(BlockKind.SERVICE, "无障碍服务已断开")

    override fun inspect(target: TargetApp): SafetyCheck =
        if (connected) inspector.inspect(target).safety else availability()

    override fun foregroundTarget(): TargetApp? = if (connected) inspector.foregroundTarget() else null

    override suspend fun swipe(target: TargetApp): GestureOutcome =
        if (connected) executor.swipe(target) else GestureOutcome.Blocked("无障碍服务已断开")

    override suspend fun doubleTap(target: TargetApp): GestureOutcome =
        if (connected) executor.doubleTap(target) else GestureOutcome.Blocked("无障碍服务已断开")

    override fun refreshOverlay() {
        if (connected) overlay.update(appContainer.engine.state.value)
    }

    private fun shutdown() {
        val wasConnected = connected
        connected = false
        serviceScope.cancel()
        environmentJob = null
        if (wasConnected) {
            val container = appContainer
            // Invalidate the task before close() can resume an outstanding gesture.
            // A quick reconnect must not hide the disconnect in the shared flow.
            container.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                container.engine.pause("无障碍服务已断开，请重新开启后继续")
            }
            container.bridge.detach(this)
        }
        if (::executor.isInitialized) executor.close()
        if (::overlay.isInitialized) overlay.close()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }
}
