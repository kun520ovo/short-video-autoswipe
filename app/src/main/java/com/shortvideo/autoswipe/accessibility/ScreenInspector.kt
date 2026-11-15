package com.shortvideo.autoswipe.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shortvideo.autoswipe.domain.*
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.platform.PermissionChecker
import com.shortvideo.autoswipe.platform.ScreenGeometry

data class ScreenSnapshot(
    val safety: SafetyCheck,
    val content: PixelRect? = null,
)

class ScreenInspector(
    private val service: AccessibilityService,
    private val geometry: ScreenGeometry,
    private val permissions: PermissionChecker,
) {
    private val power = requireNotNull(service.getSystemService(PowerManager::class.java))
    private val keyguard = requireNotNull(service.getSystemService(KeyguardManager::class.java))
    private val foreground = ForegroundAppTracker()
    private val density get() = service.resources.displayMetrics.density
    private sealed interface ForegroundWindow {
        data class App(val packageName: String?, val bounds: PixelRect) : ForegroundWindow
        data object SystemPanel : ForegroundWindow
    }

    fun record(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foreground.record(event.windowId, event.packageName?.toString())
        }
    }

    fun availability(): SafetyCheck {
        val permission = permissions.read(true)
        if (!permission.accessibility) return SafetyCheck.Blocked(BlockKind.PERMISSION, "无障碍权限已关闭")
        if (!permission.overlay) return SafetyCheck.Blocked(BlockKind.PERMISSION, "悬浮窗权限已关闭")
        if (!power.isInteractive || keyguard.isKeyguardLocked) return SafetyCheck.Blocked(BlockKind.SCREEN, "屏幕已锁定或熄灭")
        return SafetyCheck.Ready
    }

    fun inspect(target: TargetApp): ScreenSnapshot {
        val availability = availability()
        if (availability is SafetyCheck.Blocked) return ScreenSnapshot(availability)
        val window = when (val foreground = foregroundWindow()) {
            is ForegroundWindow.App -> foreground
            ForegroundWindow.SystemPanel -> return ScreenSnapshot(
                SafetyCheck.Blocked(BlockKind.GEOMETRY, "系统面板遮挡了应用，收起后自动继续"),
            )
            null -> return ScreenSnapshot(SafetyCheck.Blocked(BlockKind.GEOMETRY, "应用窗口暂时不可用"))
        }
        val appCheck = checkForeground(target.packageName, window.packageName)
        if (appCheck is SafetyCheck.Blocked) return ScreenSnapshot(appCheck)
        val screen = geometry.usableBounds()
            ?: return ScreenSnapshot(SafetyCheck.Blocked(BlockKind.GEOMETRY, "正在确认屏幕可用区域"))
        val content = GesturePlanner.contentBounds(screen, window.bounds, density)
            ?: return ScreenSnapshot(SafetyCheck.Blocked(BlockKind.GEOMETRY, "屏幕尺寸暂不可用"))
        return ScreenSnapshot(SafetyCheck.Ready, content)
    }

    fun foregroundTarget(): TargetApp? {
        val packageName = (foregroundWindow() as? ForegroundWindow.App)?.packageName ?: return null
        if (packageName == service.packageName) return null
        return try {
            val packages = service.packageManager
            val launch = packages.getLaunchIntentForPackage(packageName) ?: return null
            val component = launch.component ?: return null
            val activity = launch.resolveActivityInfo(packages, 0) ?: return null
            val application = activity.applicationInfo ?: return null
            if (!activity.enabled || !activity.exported || !application.enabled) return null
            val label = packages.getApplicationLabel(application).toString().trim().ifEmpty { packageName }
            TargetApp(packageName, component.flattenToString(), label)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun foregroundWindow(): ForegroundWindow? {
        val windows = try { service.windows } catch (_: RuntimeException) { return null }
        return try {
            // A focused/active system panel receives touches before the app underneath.
            // Our non-focusable controls, idle system bars and accessibility overlays do not.
            if (windows.any {
                it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isFocused || it.isActive) &&
                    rootPackage(it) != service.packageName
            }) return ForegroundWindow.SystemPanel
            // Keyboard and accessibility overlay windows do not replace the application.
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val focused = appWindows.firstOrNull { it.isFocused }
                ?: appWindows.firstOrNull { it.isActive }
                ?: appWindows.firstOrNull()
                ?: return null
            val bounds = Rect().also { focused.getBoundsInScreen(it) }
            ForegroundWindow.App(
                foreground.resolve(focused.id, rootPackage(focused)),
                PixelRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                windows.forEach { it.recycle() }
            }
        }
    }

    private fun rootPackage(window: AccessibilityWindowInfo): String? {
        var root: AccessibilityNodeInfo? = null
        return try {
            root = window.root
            root?.packageName?.toString()
        } catch (_: RuntimeException) {
            null
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
        }
    }
}
