package com.shortvideo.autoswipe.overlay

import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import com.shortvideo.autoswipe.domain.GesturePlanner
import com.shortvideo.autoswipe.domain.PixelPoint
import com.shortvideo.autoswipe.domain.PixelRect
import com.shortvideo.autoswipe.model.RunControlAction
import com.shortvideo.autoswipe.model.RunState
import com.shortvideo.autoswipe.platform.ScreenGeometry
import com.shortvideo.autoswipe.ui.OverlayContent
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class OverlayWindow(
    private val context: Context,
    private val geometry: ScreenGeometry,
    private val onAction: (RunControlAction) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val manager = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var host: DragHost? = null
    private var content: OverlayContent? = null
    private var params: WindowManager.LayoutParams? = null
    private var model = RunState()
    private var collapsed = false
    private var preferredPositionDp: PixelPoint? = null
    private var dragOrigin = PixelPoint(0f, 0f)
    private val touching = MutableStateFlow(false)
    private val density get() = context.resources.displayMetrics.density

    fun update(state: RunState) {
        try {
            updateWindow(state)
        } catch (_: RuntimeException) {
            close()
            onFailure("悬浮窗暂时无法显示，请检查权限与屏幕状态")
        }
    }

    private fun updateWindow(state: RunState) {
        if (!state.hasOverlayContent) {
            close()
            return
        }
        // Completion leaves the window visible; a new task must reset its appearance.
        if (model.session?.id != state.session?.id) close()
        model = state
        if (!android.provider.Settings.canDrawOverlays(context)) {
            close()
            onFailure("悬浮窗权限已关闭")
            return
        }
        if (host == null && !create()) return
        content?.render(model, collapsed)
        reposition()
    }

    fun currentBounds(): PixelRect? {
        val view = host ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return PixelRect(location[0].toFloat(), location[1].toFloat(), (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat())
    }

    suspend fun awaitTouchRelease() { touching.first { !it } }

    private fun create(): Boolean {
        val frame = DragHost(context, ::beginDrag, ::moveDrag) { touching.value = it }
        val view = OverlayContent(context,
            onCollapse = {
                frame.handles.clear()
                frame.scrollRegion.setEmpty()
                collapsed = !collapsed
                content?.render(model, collapsed)
                reposition()
            },
            onAction = onAction,
            onHandle = { key, bounds ->
                if (key == "scroll") frame.scrollRegion.set(bounds) else frame.handles[key] = bounds
            },
        )
        frame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host = frame
        content = view
        params = WindowManager.LayoutParams(
            desiredWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            @Suppress("DEPRECATION")
            gravity = Gravity.TOP or Gravity.LEFT
            title = "短视频挂机"
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        view.render(model, collapsed)
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        return try {
            reposition()
            manager.addView(frame, requireNotNull(params))
            true
        } catch (_: RuntimeException) {
            close()
            onFailure("悬浮窗暂时无法显示，请检查权限")
            false
        }
    }

    private fun desiredWidth(): Int {
        val available = (geometry.usableBounds().width - 12 * density).coerceAtLeast(100 * density)
        if (!collapsed) {
            val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.25f)
            return (190 * density * fontScale).roundToInt().coerceAtMost(available.roundToInt())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        return ceil(paint.measureText(model.compactLabel) + 24 * density).toInt().coerceIn((92 * density).roundToInt(), available.roundToInt())
    }

    private fun reposition() {
        val view = host ?: return
        val layout = params ?: return
        val bounds = geometry.usableBounds()
        val inset = 6 * density
        val usable = PixelRect(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        content?.maximumHeight = usable.height.roundToInt().coerceAtLeast(1)
        val width = desiredWidth()
        val height = view.height.takeIf { it > 0 } ?: ((if (collapsed) 44 else 170) * density).roundToInt()
        val preferred = preferredPositionDp?.let { PixelPoint(it.x * density, it.y * density) }
        val actual = GesturePlanner.overlayPosition(preferred, width.toFloat(), height.toFloat(), usable)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (model.running) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        val changed = layout.x != actual.x.roundToInt() || layout.y != actual.y.roundToInt() || layout.width != width || layout.flags != flags
        layout.x = actual.x.roundToInt()
        layout.y = actual.y.roundToInt()
        layout.width = width
        layout.flags = flags
        if (changed && view.isAttachedToWindow) {
            try { manager.updateViewLayout(view, layout) } catch (_: RuntimeException) {
                close()
                onFailure("悬浮窗连接已中断")
            }
        }
    }

    private fun beginDrag() {
        val bounds = currentBounds()
        dragOrigin = if (bounds != null) PixelPoint(bounds.left, bounds.top) else PixelPoint(0f, 0f)
    }

    private fun moveDrag(dx: Float, dy: Float) {
        val view = host ?: return
        val bounds = geometry.usableBounds()
        val margin = 6 * density
        val clamped = GesturePlanner.clampWindow(
            PixelPoint(dragOrigin.x + dx, dragOrigin.y + dy), view.width.toFloat(), view.height.toFloat(),
            PixelRect(bounds.left + margin, bounds.top + margin, bounds.right - margin, bounds.bottom - margin),
        )
        preferredPositionDp = PixelPoint(clamped.x / density, clamped.y / density)
        reposition()
    }

    fun close() {
        val view = host
        host = null
        view?.handles?.clear()
        view?.scrollRegion?.setEmpty()
        touching.value = false
        if (view?.isAttachedToWindow == true) runCatching { manager.removeViewImmediate(view) }
        content = null
        params = null
        model = RunState()
        collapsed = false
        preferredPositionDp = null
    }
}
