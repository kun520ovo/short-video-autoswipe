package com.shortvideo.autoswipe.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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
        val appCheck = checkForeground(target.packageName, foregroundPackage())
        if (appCheck is SafetyCheck.Blocked) return ScreenSnapshot(appCheck)
        val bounds = geometry.usableBounds()
        val content = PixelRect(
            bounds.left + 8 * density, bounds.top + bounds.height * .10f,
            bounds.right - 8 * density, bounds.bottom - bounds.height * .12f,
        )
        if (content.width <= 0 || content.height <= 0) {
            return ScreenSnapshot(SafetyCheck.Blocked(BlockKind.GEOMETRY, "屏幕尺寸暂不可用"))
        }
        return ScreenSnapshot(SafetyCheck.Ready, content)
    }

    fun foregroundTarget(): TargetApp? {
        val packageName = foregroundPackage() ?: return null
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

    private fun foregroundPackage(): String? {
        val windows = try { service.windows } catch (_: RuntimeException) { return null }
        return try {
            // Keyboard and overlay windows never replace the foreground application.
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val focused = appWindows.firstOrNull { it.isFocused }
                ?: appWindows.firstOrNull { it.isActive }
                ?: appWindows.firstOrNull()
            foreground.resolve(focused?.id, focused?.let(::rootPackage))
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
