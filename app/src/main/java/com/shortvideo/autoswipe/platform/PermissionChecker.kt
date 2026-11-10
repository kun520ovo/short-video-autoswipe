package com.shortvideo.autoswipe.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import com.shortvideo.autoswipe.accessibility.SwipeAccessibilityService

data class PermissionState(val accessibility: Boolean, val overlay: Boolean, val connected: Boolean) {
    val granted get() = accessibility && overlay
}

enum class PermissionKind { ACCESSIBILITY, OVERLAY }

class PermissionChecker(private val context: Context) {
    fun read(connected: Boolean): PermissionState {
        val expected = ComponentName(context, SwipeAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val enabled = runCatching {
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any { item ->
                val info = item.resolveInfo?.serviceInfo
                info != null && ComponentName(info.packageName, info.name) == expected
            } ?: false
        }.getOrDefault(false)
        val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        return PermissionState(enabled, overlay, connected)
    }

    fun intent(kind: PermissionKind): Intent = when (kind) {
        PermissionKind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionKind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + context.packageName).toUri())
    }
}
