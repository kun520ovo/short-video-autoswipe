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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shortvideo.autoswipe.domain.GesturePlanner
import com.shortvideo.autoswipe.domain.PixelPoint
import com.shortvideo.autoswipe.domain.PixelRect
import com.shortvideo.autoswipe.model.RunState
import com.shortvideo.autoswipe.model.RunControlAction
import com.shortvideo.autoswipe.platform.ScreenGeometry
import com.shortvideo.autoswipe.ui.AutoSwipeTheme
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
    private var compose: ComposeView? = null
    private var owner: WindowOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var model by mutableStateOf(RunState())
    private var collapsed by mutableStateOf(false)
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
        val lifecycle = WindowOwner()
        val frame = DragHost(context, ::beginDrag, ::moveDrag) { touching.value = it }
        val content = ComposeView(context)
        frame.setViewTreeLifecycleOwner(lifecycle)
        frame.setViewTreeViewModelStoreOwner(lifecycle)
        frame.setViewTreeSavedStateRegistryOwner(lifecycle)
        content.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycle))
        frame.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        owner = lifecycle
        host = frame
        compose = content
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
        content.setContent {
            AutoSwipeTheme {
                OverlayContent(
                    state = model,
                    collapsed = collapsed,
                    onCollapse = {
                        frame.handles.clear()
                        collapsed = !collapsed
                        reposition()
                    },
                    onAction = onAction,
                    onHandle = { key, rect -> frame.handles[key] = rect },
                )
            }
        }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        return try {
            reposition()
            lifecycle.resume()
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
        touching.value = false
        if (view?.isAttachedToWindow == true) runCatching { manager.removeViewImmediate(view) }
        compose?.disposeComposition()
        compose = null
        owner?.destroy()
        owner = null
        params = null
        model = RunState()
        collapsed = false
        preferredPositionDp = null
    }
}

private class WindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
