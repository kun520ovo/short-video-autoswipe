package com.shortvideo.autoswipe.platform

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.shortvideo.autoswipe.domain.PixelRect

class ScreenGeometry(context: Context) {
    private val manager = requireNotNull(context.getSystemService(WindowManager::class.java))
    private val resources = context.resources

    fun usableBounds(): PixelRect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return PixelRect(
                (bounds.left + insets.left).toFloat(), (bounds.top + insets.top).toFloat(),
                (bounds.right - insets.right).toFloat(), (bounds.bottom - insets.bottom).toFloat(),
            )
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
        val status = resources.getIdentifier("status_bar_height", "dimen", "android")
        val navigation = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return PixelRect(
            0f,
            if (status != 0) resources.getDimensionPixelSize(status).toFloat() else 0f,
            metrics.widthPixels.toFloat(),
            metrics.heightPixels - if (navigation != 0) resources.getDimensionPixelSize(navigation).toFloat() else 0f,
        )
    }
}
