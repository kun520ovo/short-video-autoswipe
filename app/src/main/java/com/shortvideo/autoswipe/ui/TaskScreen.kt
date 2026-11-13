package com.shortvideo.autoswipe.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.domain.GESTURE_RECOVERY_GUIDE
import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.platform.PermissionKind
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.hypot

data class TaskScreenActions(
    val edit: (String, String) -> Unit,
    val touched: (String) -> Unit,
    val setLike: (Boolean) -> Unit,
    val openPicker: () -> Unit,
    val closePicker: () -> Unit,
    val selectApp: (TargetApp) -> Unit,
    val start: () -> Unit,
    val pause: () -> Unit,
    val stop: () -> Unit,
    val allPermissions: () -> Unit,
    val permission: (PermissionKind) -> Unit,
    val loadIcon: suspend (TargetApp) -> Bitmap?,
    val openRepository: () -> Unit,
)

/** One reusable view tree; the existing ViewModel remains the only source of form/task state. */
class TaskScreen(context: Context, private val actions: TaskScreenActions) : ScrollView(context) {
    private val palette = context.appPalette()
    private val scope = MainScope()
    private val column = LinearLayout(context)
    private val inputs = linkedMapOf<String, DraftInput>()
    private val errors = linkedMapOf<String, TextView>()
    private val appField: LinearLayout
    private val matchRow = LinearLayout(context)
    private val matchIcon = InstalledIcon(context, scope, actions.loadIcon)
    private val matchLabel = context.label("", 12f, palette.ink)
    private val matchStatus = context.label("已匹配", 11f, palette.accent)
    private val matchAction = textButton("") { dismissKeyboard(); actions.openPicker() }
    private val likeSwitch = LikeSwitch(context)
    private val likeRow: LinearLayout
    private val accessibility: PermissionRow
    private val overlay: PermissionRow
    private val message = context.label("", 12f, palette.error)
    private val continueHint = context.label("", 12f, palette.ink)
    private val primary = ControlButton(context)
    private val controls = LinearLayout(context)
    private val pause = ControlButton(context)
    private val stop = ControlButton(context)
    private val lockedHint = context.label("本次参数已锁定，关闭后可修改", 11f, palette.muted)
    private var current: MainUiState? = null
    private var syncingLike = false
    private var picker: AppPickerDialog? = null
    private var restoredFocus: Bundle? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dismissEligible = false
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private var appHasError = false

    init {
        id = R.id.task_screen
        isFillViewport = true
        setBackgroundColor(palette.background)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(context.dp(24), 0, context.dp(24), context.dp(24))
        column.isFocusableInTouchMode = true
        column.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        addView(column, LayoutParams(-1, -2))

        add(context.label("短视频挂机", 23f, palette.ink, true, 30f).apply {
            id = R.id.repository_title
            contentDescription = context.getString(R.string.repository_title_description)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissKeyboard(); actions.openRepository() }
        }, top = 24, bottom = 26)
        add(context.label("应用名称", 12f, palette.muted), bottom = 10)
        appField = plainInput("app", R.id.input_app, "应用名称", false, "填写手机里的应用名称", null, 52)
        add(appField)
        addError("app")
        matchRow.id = R.id.match_row
        matchRow.orientation = LinearLayout.HORIZONTAL
        matchRow.gravity = Gravity.CENTER_VERTICAL
        matchRow.minimumHeight = context.dp(32)
        matchRow.setPadding(0, context.dp(7), 0, 0)
        matchRow.addView(matchIcon, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(7) })
        matchLabel.setSingleLine()
        matchLabel.ellipsize = TextUtils.TruncateAt.END
        matchRow.addView(matchLabel, LinearLayout.LayoutParams(0, -2, 1f))
        matchRow.addView(matchStatus, LinearLayout.LayoutParams(-2, -2))
        matchAction.id = R.id.match_action
        matchRow.addView(matchAction, LinearLayout.LayoutParams(-2, context.dp(48)))
        add(matchRow)
        addSpace(8)

        add(settingRow("滑屏次数", plainInput("count", R.id.input_count, "滑屏次数", true, "填写次数", "次", 44)))
        addError("count")
        divider()
        val time = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.rounded(palette.field, 10)
            setPadding(context.dp(10), 0, context.dp(10), 0)
        }
        time.addView(input("min", R.id.input_min, "最短停留时间", true, true, "最短", 16f, 12f), LinearLayout.LayoutParams(0, -1, 1f))
        time.addView(context.label("–", 13f, palette.muted), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = context.dp(5); marginEnd = context.dp(5)
        })
        time.addView(input("max", R.id.input_max, "最长停留时间", true, true, "最长", 16f, 12f), LinearLayout.LayoutParams(0, -1, 1f))
        time.addView(context.label("秒", 12f, palette.muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(5) })
        add(settingRow("停留时间", time))
        addError("time")
        divider()

        likeSwitch.id = R.id.random_like
        likeSwitch.setOnCheckedChangeListener { _, checked -> if (!syncingLike) actions.setLike(checked) }
        add(settingRow("随机双击点赞", likeSwitch, 52, 48))
        likeRow = settingRow("点赞概率", plainInput("like", R.id.input_like, "点赞概率", true, "概率", "%", 44), 94)
        (likeRow.getChildAt(0) as TextView).apply { setTextColor(palette.muted); textSize = 12f }
        likeRow.minimumHeight = context.dp(44)
        add(likeRow, bottom = 12)
        addError("like")
        divider()

        add(context.label("运行权限", 12f, palette.muted), top = 12, bottom = 5)
        accessibility = PermissionRow("无障碍服务", PermissionKind.ACCESSIBILITY, R.id.permission_accessibility)
        overlay = PermissionRow("悬浮窗", PermissionKind.OVERLAY, R.id.permission_overlay)
        add(accessibility.row)
        add(overlay.row)
        message.id = R.id.run_message
        add(message, top = 8)
        continueHint.id = R.id.continue_hint
        continueHint.text = SpannableString("请切到要操作的应用，点击悬浮窗“继续”。").apply {
            val start = indexOf("“继续”")
            setSpan(StyleSpan(Typeface.BOLD), start, start + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add(continueHint, top = 12)
        addSpace(14)
        primary.id = R.id.primary_action
        primary.setOnClickListener {
            dismissKeyboard()
            if (current?.permissions?.granted == true) actions.start() else actions.allPermissions()
        }
        add(primary, height = 52)
        controls.orientation = LinearLayout.HORIZONTAL
        pause.id = R.id.pause_action
        stop.id = R.id.stop_action
        pause.setOnClickListener { dismissKeyboard(); actions.pause() }
        stop.setOnClickListener { actions.stop() }
        controls.addView(pause, LinearLayout.LayoutParams(0, context.dp(52), 1f).apply { marginEnd = context.dp(10) })
        controls.addView(stop, LinearLayout.LayoutParams(0, context.dp(52), 1f))
        add(controls)
        lockedHint.gravity = Gravity.CENTER
        add(lockedHint, top = 13)
        render(MainUiState())
    }

    fun render(ui: MainUiState) {
        if (current == ui) return
        val wasLocked = current?.let { it.run.locked || !it.ready } ?: true
        current = ui
        val locked = ui.run.locked || !ui.ready
        if (locked && !wasLocked) dismissKeyboard()
        inputs.getValue("app").syncValue(ui.draft.appName)
        inputs.getValue("count").syncValue(ui.draft.count)
        inputs.getValue("min").syncValue(ui.draft.minSeconds)
        inputs.getValue("max").syncValue(ui.draft.maxSeconds)
        inputs.getValue("like").syncValue(ui.draft.likePercent)
        inputs.values.forEach { it.isEnabled = !locked }
        val appError = ui.errors["app"] != null
        if (appHasError != appError) {
            appHasError = appError
            appField.background = context.rounded(palette.field, 10, palette.error.takeIf { appError })
        }
        for ((field, label) in errors) {
            val error = if (field == "app") ui.errors[field] ?: ui.appLoadError else ui.errors[field]
            label.show(error != null && (field != "like" || ui.draft.randomLike))
            label.showText(error.orEmpty())
        }
        val selected = ui.match.selected
        matchRow.show(ui.draft.appName.isNotBlank() && (selected != null || ui.appLoading || ui.match.candidates.size > 1))
        matchIcon.show(selected != null)
        if (selected != null) matchIcon.bind(selected)
        matchLabel.showText(selected?.label ?: if (ui.appLoading) "正在查找应用" else "找到 ${ui.match.candidates.size} 个应用")
        matchLabel.setTextColor(if (ui.appLoading && selected == null) palette.muted else palette.ink)
        matchStatus.show(selected != null)
        matchAction.show(ui.match.candidates.size > 1 && !locked && !ui.appLoading)
        matchAction.showText(if (selected == null) "选择" else "更换")
        syncingLike = true
        try { likeSwitch.isChecked = ui.draft.randomLike } finally { syncingLike = false }
        likeSwitch.isEnabled = !locked
        likeRow.show(ui.draft.randomLike)

        accessibility.render(ui.permissions.accessibility)
        overlay.render(ui.permissions.overlay)
        val paused = ui.run.status == RunStatus.PAUSED
        message.show(ui.message != null)
        message.showText(ui.message.orEmpty())
        message.setTypeface(null, if (paused && ui.message == "任务已暂停") Typeface.BOLD else Typeface.NORMAL)
        continueHint.show(paused && ui.run.pauseReason != GESTURE_RECOVERY_GUIDE)
        primary.show(!ui.run.locked)
        controls.show(ui.run.locked)
        lockedHint.show(ui.run.locked)
        pause.show(!paused)
        pause.render("暂停", palette.accent, palette.background, R.drawable.ic_pause)
        stop.render("关闭", palette.field, palette.ink, R.drawable.ic_stop)
        val grant = !ui.permissions.granted
        primary.render(ui.primaryLabel, if (grant) palette.field else palette.accent,
            if (grant) palette.ink else palette.background, enabled = ui.ready && (grant || ui.permissions.connected))
        syncPicker(ui)
        if (ui.ready) {
            restoredFocus?.let { saved ->
                if (!locked) inputs[saved.getString("field")]?.let { edit ->
                    edit.requestFocus()
                    edit.setSelection(saved.getInt("start").coerceIn(0, edit.length()), saved.getInt("end").coerceIn(0, edit.length()))
                }
            }
            restoredFocus = null
        }
    }

    fun saveEditingState(outState: Bundle) {
        inputs.entries.firstOrNull { it.value.hasFocus() }?.let { (field, input) ->
            outState.putBundle("native_focus", Bundle().apply {
                putString("field", field)
                putInt("start", input.selectionStart)
                putInt("end", input.selectionEnd)
            })
        }
    }

    fun restoreEditingState(saved: Bundle?) { restoredFocus = saved?.getBundle("native_focus") }

    private fun syncPicker(ui: MainUiState) {
        if (ui.picker && ui.ready && !ui.run.locked && isAttachedToWindow) {
            val dialog = picker ?: AppPickerDialog(context, scope, actions.loadIcon, actions.selectApp, actions.closePicker).also {
                picker = it
            }
            dialog.updateApps(ui.match.candidates)
            if (!dialog.isShowing) dialog.show()
        } else {
            picker?.closeFromState()
            picker = null
        }
    }

    fun dismissKeyboard() {
        column.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downScroll = scrollY
                dismissEligible = !hitsInteractiveChild(this, event.rawX.toInt(), event.rawY.toInt())
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> dismissEligible = false
            MotionEvent.ACTION_MOVE -> if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) dismissEligible = false
        }
        // Record the DOWN hit before EditText can scroll itself or resize the window for the IME.
        val dismiss = event.actionMasked == MotionEvent.ACTION_UP && dismissEligible &&
            event.pointerCount == 1 && scrollY == downScroll &&
            hypot(event.rawX - downX, event.rawY - downY) <= touchSlop
        val handled = super.dispatchTouchEvent(event)
        if (dismiss) dismissKeyboard()
        if (event.actionMasked == MotionEvent.ACTION_UP) dismissEligible = false
        return handled
    }

    private fun hitsInteractiveChild(parent: ViewGroup, x: Int, y: Int): Boolean {
        val bounds = Rect()
        for (index in parent.childCount - 1 downTo 0) {
            val view = parent.getChildAt(index)
            if (view.visibility != VISIBLE || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) continue
            if (view is DraftInput || view.isClickable || view.isLongClickable) return true
            if (view is ViewGroup && hitsInteractiveChild(view, x, y)) return true
        }
        return false
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); current?.let(::syncPicker) }

    override fun onDetachedFromWindow() {
        picker?.closeFromState()
        picker = null
        super.onDetachedFromWindow()
    }

    fun dispose() {
        picker?.closeFromState()
        picker = null
        scope.cancel()
    }

    private fun add(view: View, top: Int = 0, bottom: Int = 0, height: Int = -2) {
        column.addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else context.dp(height)).apply {
            topMargin = context.dp(top); bottomMargin = context.dp(bottom)
        })
    }

    private fun addSpace(height: Int) = add(View(context), height = height)
    private fun divider() = add(View(context).apply { setBackgroundColor(palette.line) }, height = 1)

    private fun addError(field: String) {
        val error = context.label("", 11f, palette.error).apply { tag = "error_$field" }
        errors[field] = error
        add(error, top = 3, bottom = 9)
    }

    private fun input(field: String, viewId: Int, description: String, numeric: Boolean, centered: Boolean,
        placeholder: String, size: Float, placeholderSize: Float): DraftInput =
        DraftInput(context, numeric, centered, placeholder, size, placeholderSize,
            when (field) { "app" -> 60; "count" -> 8; "like" -> 4; else -> 6 },
            { actions.edit(field, it) }, { actions.touched(if (field == "min" || field == "max") "time" else field) },
            ::dismissKeyboard).also {
            it.id = viewId
            it.contentDescription = description
            inputs[field] = it
        }

    private fun plainInput(field: String, viewId: Int, description: String, numeric: Boolean,
        placeholder: String, unit: String?, height: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(height)
        background = context.rounded(palette.field, 10)
        setPadding(context.dp(12), 0, context.dp(12), 0)
        val edit = input(field, viewId, description, numeric, false, placeholder, if (numeric) 17f else 16f, if (numeric) 14f else 16f)
        addView(edit, LinearLayout.LayoutParams(0, -1, 1f))
        if (unit != null) addView(context.label(unit, 12f, palette.muted), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = context.dp(6)
        })
        isClickable = true
        setOnClickListener {
            if (edit.isEnabled) {
                edit.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun settingRow(label: String, control: View, width: Int = 150, height: Int = 44) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(62)
            addView(context.label(label, 14f, palette.ink), LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = context.dp(8)
            })
            addView(control, LinearLayout.LayoutParams(context.dp(width), context.dp(height)))
        }

    private fun textButton(text: String, clicked: () -> Unit) = context.label(text, 14f, palette.accent, true).apply {
        gravity = Gravity.CENTER
        minWidth = context.dp(64)
        minHeight = context.dp(48)
        setPadding(context.dp(12), 0, context.dp(12), 0)
        isClickable = true
        isFocusable = true
        ripple((palette.accent and 0x00FFFFFF) or 0x22000000, 24)
        setOnClickListener { clicked() }
    }

    private inner class PermissionRow(label: String, private val kind: PermissionKind, viewId: Int) {
        val row = LinearLayout(context)
        private val status = context.label("", 11f, palette.error)
        private val button = textButton("") { actions.permission(kind) }
        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = context.dp(46)
            row.addView(context.label(label, 12f, palette.ink), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(8) })
            button.id = viewId
            row.addView(button, LinearLayout.LayoutParams(-2, context.dp(48)))
        }
        fun render(granted: Boolean) {
            status.showText(if (kind == PermissionKind.ACCESSIBILITY) {
                if (granted) "已开启" else "未开启"
            } else if (granted) "已授权" else "未授权")
            status.setTextColor(if (granted) palette.accent else palette.error)
            button.showText(if (granted) "管理" else if (kind == PermissionKind.OVERLAY) "去授权" else "去开启")
        }
    }
}
