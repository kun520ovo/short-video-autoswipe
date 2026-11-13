package com.shortvideo.autoswipe.ui

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.robolectric.Shadows.shadowOf
import java.time.Duration

internal fun layoutView(view: View, widthDp: Int, heightDp: Int) {
    val width = view.context.dp(widthDp)
    val height = view.context.dp(heightDp)
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    view.layout(0, 0, width, height)
}

internal fun settleClicks() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getPressedStateDuration().toLong() + 1))
}

internal fun centerOnScreen(view: View): Pair<Float, Float> {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return location[0] + view.width / 2f to location[1] + view.height / 2f
}

internal fun touch(root: View, action: Int, rawX: Float, rawY: Float, downTime: Long = SystemClock.uptimeMillis()) {
    val location = IntArray(2)
    root.getLocationOnScreen(location)
    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0).also {
        it.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
        root.dispatchTouchEvent(it)
        it.recycle()
    }
}

internal fun tap(root: View, view: View) {
    val (x, y) = centerOnScreen(view)
    val time = SystemClock.uptimeMillis()
    touch(root, MotionEvent.ACTION_DOWN, x, y, time)
    touch(root, MotionEvent.ACTION_UP, x, y, time)
    settleClicks()
}
