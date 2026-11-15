package com.shortvideo.autoswipe.platform

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.shortvideo.autoswipe.domain.PixelRect

class ScreenGeometry(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var insetsView: View? = null
    private var legacyBounds: LegacyBounds? = null

    private data class LegacyDisplay(val width: Int, val height: Int, val rotation: Int)
    private data class LegacyBounds(val display: LegacyDisplay, val bounds: PixelRect)

    fun usableBounds(): PixelRect? {
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
        return legacyBounds?.takeIf { insetsView?.isAttachedToWindow == true && it.display == legacyDisplay() }?.bounds
    }

    /** Temporary placement for controls; gestures must wait for [usableBounds]. */
    fun displayBounds(): PixelRect {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = manager.currentWindowMetrics.bounds
            return PixelRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        }
        val display = legacyDisplay()
        return PixelRect(0f, 0f, display.width.toFloat(), display.height.toFloat())
    }

    /** API 29 has no WindowMetrics. A transparent, untouchable accessibility window
     * receives actual stable insets on every edge, even when the control window is
     * far from the bars. It exists only alongside the visible floating controls. */
    @Suppress("DEPRECATION")
    fun observeInsets(onChanged: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30 || insetsView != null) return
        val view = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        var pending: Pair<LegacyDisplay, WindowInsets>? = null
        fun publishBounds() {
            if (insetsView !== view || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
            val (display, insets) = pending ?: return
            if (display != legacyDisplay() || view.width > display.width || view.height > display.height) return
            // API 29 stable insets remain available with hidden bars and exclude the IME.
            // Read the delivered snapshot directly, before rootWindowInsets may update.
            val cutout = insets.displayCutout
            val left = maxOf(insets.stableInsetLeft, cutout?.safeInsetLeft ?: 0)
            val top = maxOf(insets.stableInsetTop, cutout?.safeInsetTop ?: 0)
            val right = maxOf(insets.stableInsetRight, cutout?.safeInsetRight ?: 0)
            val bottom = maxOf(insets.stableInsetBottom, cutout?.safeInsetBottom ?: 0)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val bounds = PixelRect(
                (location[0] + left).coerceAtLeast(0).toFloat(),
                (location[1] + top).coerceAtLeast(0).toFloat(),
                (location[0] + view.width - right).coerceAtMost(display.width).toFloat(),
                (location[1] + view.height - bottom).coerceAtMost(display.height).toFloat(),
            )
            val next = LegacyBounds(display, bounds).takeIf { bounds.width > 0 && bounds.height > 0 }
            if (legacyBounds != next) {
                legacyBounds = next
                onChanged()
            }
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            pending = legacyDisplay() to insets
            publishBounds()
            insets
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishBounds() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = 0f
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "屏幕安全边距"
        }
        insetsView = view
        try {
            manager.addView(view, params)
            view.requestApplyInsets()
        } catch (error: RuntimeException) {
            stopObserving()
            throw error
        }
    }

    fun stopObserving() {
        val view = insetsView
        insetsView = null
        legacyBounds = null
        view?.setOnApplyWindowInsetsListener(null)
        if (view?.isAttachedToWindow == true) runCatching { manager.removeViewImmediate(view) }
    }

    @Suppress("DEPRECATION")
    private fun legacyDisplay(): LegacyDisplay {
        val display = manager.defaultDisplay
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        return LegacyDisplay(metrics.widthPixels, metrics.heightPixels, display.rotation)
    }
}
