package com.shortvideo.autoswipe.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout

/** Keep the icon and text centered together, with one accessible click target. */
class ControlButton(context: Context, private val compact: Boolean = false) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = context.label("", if (compact) 12f else 16f, Color.WHITE, true,
        if (compact) 16f else 22f)
    private var colors: Pair<Int, Int>? = null
    private var iconResource: Int? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = context.dp(if (compact) 44 else 52)
        setPadding(context.dp(if (compact) 7 else 12), 0, context.dp(if (compact) 7 else 12), 0)
        isClickable = true
        isFocusable = true
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        label.setSingleLine()
        addView(icon, LayoutParams(context.dp(if (compact) 16 else 24), context.dp(if (compact) 16 else 24)).apply {
            marginEnd = context.dp(if (compact) 4 else 8)
        })
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(text: String, background: Int, foreground: Int, drawable: Int? = null, enabled: Boolean = true) {
        label.showText(text)
        contentDescription = text
        icon.show(drawable != null)
        if (iconResource != drawable) {
            iconResource = drawable
            if (drawable != null) icon.setImageResource(drawable) else icon.setImageDrawable(null)
        }
        if (colors != (background to foreground)) {
            colors = background to foreground
            this.background = context.rounded(background, if (compact) 8 else 13)
            ripple((foreground and 0x00FFFFFF) or 0x22000000, if (compact) 8 else 13)
            label.setTextColor(foreground)
            icon.setColorFilter(foreground)
        }
        isEnabled = enabled
        alpha = if (enabled) 1f else .48f
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}
