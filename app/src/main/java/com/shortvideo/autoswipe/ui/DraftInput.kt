package com.shortvideo.autoswipe.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged

class DraftInput(
    context: Context,
    numeric: Boolean,
    private val centered: Boolean,
    placeholder: String,
    size: Float,
    placeholderSize: Float,
    limit: Int,
    changed: (String) -> Unit,
    blurred: () -> Unit,
    done: () -> Unit,
) : EditText(context) {
    private var synchronizing = false
    private var horizontalOverflow = false

    init {
        val palette = context.appPalette()
        background = null
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setSingleLine()
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        // A scrolling TextView uses a very wide internal layout. Centering that layout
        // places short values outside the visible field; scroll only when text overflows.
        setHorizontallyScrolling(!centered)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or (if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START)
        setTextColor(palette.ink)
        setHintTextColor(palette.muted)
        hint = SpannableString(placeholder).apply {
            setSpan(AbsoluteSizeSpan(context.sp(placeholderSize)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textCursorDrawable = textCursorDrawable?.mutate()?.apply {
            setTintList(ColorStateList.valueOf(palette.accent))
        }
        filters = arrayOf(InputFilter.LengthFilter(limit))
        // The ViewModel/DataStore owns the draft; view restoration must not overwrite it.
        isSaveEnabled = false
        setOnFocusChangeListener { _, focused -> if (!focused) blurred() }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { done(); true } else false
        }
        doAfterTextChanged {
            updateCentering(width)
            if (!synchronizing) changed(it?.toString().orEmpty())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            updateCentering(MeasureSpec.getSize(widthMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateCentering(availableWidth: Int) {
        if (!centered || availableWidth <= 0) return
        val overflow = paint.measureText(text?.toString().orEmpty()) > availableWidth - compoundPaddingLeft - compoundPaddingRight
        if (horizontalOverflow == overflow) return
        horizontalOverflow = overflow
        setHorizontallyScrolling(overflow)
        gravity = Gravity.CENTER_VERTICAL or (if (overflow) Gravity.START else Gravity.CENTER_HORIZONTAL)
        if (!overflow) scrollTo(0, 0)
    }

    fun syncValue(value: String) {
        // Equal text must retain the Editable, IME composing spans and cursor selection.
        if (text?.toString() == value) return
        val start = selectionStart
        val end = selectionEnd
        synchronizing = true
        try {
            text?.replace(0, text?.length ?: 0, value)
            if (hasFocus()) setSelection(start.coerceIn(0, value.length), end.coerceIn(0, value.length))
            else setSelection(value.length)
        } finally {
            synchronizing = false
        }
    }
}
