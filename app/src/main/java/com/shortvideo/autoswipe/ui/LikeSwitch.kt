package com.shortvideo.autoswipe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton

/** A platform CompoundButton preserves tap, keyboard and accessibility toggle behavior. */
class LikeSwitch(context: Context) : CompoundButton(context) {
    private val palette = context.appPalette()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buttonDrawable = null
        background = null
        isClickable = true
        isFocusable = true
        contentDescription = "随机双击点赞"
        setPadding(0, 0, 0, 0)
        ripple((palette.accent and 0x00FFFFFF) or 0x22000000, 24)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(context.dp(52), widthMeasureSpec), resolveSize(context.dp(48), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = resources.displayMetrics.density
        val left = (width - 52 * scale) / 2
        val top = (height - 32 * scale) / 2
        val track = RectF(left, top, left + 52 * scale, top + 32 * scale)
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) palette.accent else palette.line
        paint.alpha = if (isEnabled) 255 else 110
        canvas.drawRoundRect(track, 16 * scale, 16 * scale, paint)
        if (!isChecked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * scale
            paint.color = palette.muted
            paint.alpha = if (isEnabled) 255 else 110
            track.inset(scale, scale)
            canvas.drawRoundRect(track, 15 * scale, 15 * scale, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) palette.background else palette.muted
        paint.alpha = if (isEnabled) 255 else 110
        canvas.drawCircle(left + (if (isChecked) 36 else 16) * scale, height / 2f,
            (if (isChecked) 12 else 8) * scale, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
    }
}
