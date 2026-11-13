package com.shortvideo.autoswipe.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

data class AppPalette(
    val background: Int, val ink: Int, val muted: Int, val field: Int,
    val line: Int, val accent: Int, val error: Int,
)

fun Context.appPalette(): AppPalette = if (
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
) {
    AppPalette(0xFF161616.toInt(), 0xFFF2F2F2.toInt(), 0xFFB8B8B8.toInt(), 0xFF262626.toInt(),
        0xFF373737.toInt(), 0xFF80D6AB.toInt(), 0xFFEBAAA0.toInt())
} else {
    AppPalette(Color.WHITE, 0xFF24372F.toInt(), 0xFF748179.toInt(), 0xFFF3F5F2.toInt(),
        0xFFEDF0EC.toInt(), 0xFF267D5B.toInt(), 0xFF985A52.toInt())
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
internal fun Context.sp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).roundToInt()

internal fun Context.rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

internal fun View.ripple(color: Int, radius: Int) {
    foreground = RippleDrawable(ColorStateList.valueOf(color), null, context.rounded(Color.WHITE, radius))
}

internal fun Context.label(value: CharSequence, size: Float, color: Int, medium: Boolean = false,
    lineHeight: Float = size * 1.5f) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    includeFontPadding = false
    setLineHeight(sp(lineHeight))
}

internal fun TextView.showText(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

internal fun View.show(visible: Boolean) {
    val next = if (visible) View.VISIBLE else View.GONE
    if (visibility != next) visibility = next
}
