package com.shortvideo.autoswipe.overlay

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Use raw screen coordinates because moving a WindowManager window changes
 * Compose-local coordinates. Intercepting a drag sends CANCEL to a pending click.
 */
class DragHost(
    context: Context,
    private val onStart: () -> Unit,
    private val onMove: (Float, Float) -> Unit,
    private val onTouchActive: (Boolean) -> Unit,
) : FrameLayout(context) {
    val handles = mutableMapOf<String, RectF>()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var eligible = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            dragging = false
            eligible = false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouchActive(true)
            eligible = handles.values.any { it.contains(event.x, event.y) }
            dragging = false
            startX = event.rawX
            startY = event.rawY
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) {
            // Android will not send UP to a view that rejected this DOWN.
            eligible = false
            onTouchActive(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            eligible = false
            dragging = false
            onTouchActive(false)
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (eligible && event.actionMasked == MotionEvent.ACTION_MOVE && !dragging &&
            hypot(event.rawX - startX, event.rawY - startY) > slop) {
            dragging = true
            onStart()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!eligible) return super.onTouchEvent(event)
        if (!dragging && event.actionMasked == MotionEvent.ACTION_MOVE &&
            hypot(event.rawX - startX, event.rawY - startY) > slop) {
            dragging = true
            onStart()
        }
        if (dragging && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)) {
            onMove(event.rawX - startX, event.rawY - startY)
        }
        if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
