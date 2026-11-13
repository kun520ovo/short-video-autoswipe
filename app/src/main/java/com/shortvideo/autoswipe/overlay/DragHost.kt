package com.shortvideo.autoswipe.overlay

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Use raw screen coordinates because moving a WindowManager window changes
 * view-local coordinates. Intercepting a drag sends CANCEL to a pending click.
 */
class DragHost(
    context: Context,
    private val onStart: () -> Unit,
    private val onMove: (Float, Float) -> Unit,
    private val onTouchActive: (Boolean) -> Unit,
) : FrameLayout(context) {
    val handles = mutableMapOf<String, RectF>()
    val scrollRegion = RectF()
    private var scrolling = false
    private var cancelled = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var eligible = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouchActive(true)
            eligible = handles.values.any { it.contains(event.x, event.y) }
            scrolling = scrollRegion.contains(event.x, event.y)
            cancelled = false
            dragging = false
            startX = event.rawX
            startY = event.rawY
        }
        val cancelClick = event.pointerCount > 1 ||
            (!eligible && !scrolling && event.actionMasked == MotionEvent.ACTION_MOVE &&
                hypot(event.rawX - startX, event.rawY - startY) > slop)
        if (!cancelled && cancelClick) {
            MotionEvent.obtain(event).also {
                it.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(it)
                it.recycle()
            }
            cancelled = true
            dragging = false
            eligible = false
        }
        val handled = if (cancelled) true else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) {
            // Android will not send UP to a view that rejected this DOWN.
            eligible = false
            onTouchActive(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            eligible = false
            dragging = false
            scrolling = false
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
