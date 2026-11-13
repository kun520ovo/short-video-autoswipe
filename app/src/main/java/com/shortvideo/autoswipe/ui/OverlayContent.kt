package com.shortvideo.autoswipe.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.model.RunControlAction
import com.shortvideo.autoswipe.model.RunStage
import com.shortvideo.autoswipe.model.RunState
import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.model.intervalSeconds
import java.text.SimpleDateFormat
import java.util.Date

/** The foreground service reuses these controls; no UI composition or lifecycle owner is needed. */
class OverlayContent(
    context: Context,
    onCollapse: () -> Unit,
    private val onAction: (RunControlAction) -> Unit,
    private val onHandle: (String, RectF) -> Unit,
) : FrameLayout(context) {
    private val compact = context.label("", 12f, Color.WHITE, true, 18f)
    private val header = LinearLayout(context)
    private val status = context.label("", 11f, Color.WHITE)
    private val titleHandle = LinearLayout(context)
    private val counts = CountRow(context)
    private val log = context.label("", 12f, Color.WHITE, true, 18f)
    private val likeLog = context.label("", 12f, Color.WHITE, true, 18f)
    private val logLines = LinearLayout(context)
    private val logScroll = ScrollView(context)
    private val controls = LinearLayout(context)
    private val action = ControlButton(context, true)
    private val stop = ControlButton(context, true)
    private val expanded: OverlayStack
    private val formatter = SimpleDateFormat("HH:mm", resources.configuration.locales[0])
    private var current = RunState()
    private var collapsed = false
    private var logKey: Pair<String?, String?>? = null

    var maximumHeight: Int = Int.MAX_VALUE
        set(value) {
            val next = value.coerceAtLeast(1)
            if (field != next) { field = next; requestLayout() }
        }

    init {
        compact.id = R.id.overlay_compact
        compact.setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        compact.gravity = Gravity.CENTER
        compact.minHeight = context.dp(44)
        compact.setSingleLine()
        compact.ellipsize = TextUtils.TruncateAt.END
        compact.fontFeatureSettings = "tnum"
        compact.background = context.rounded(0x94000000.toInt(), 24)
        compact.isClickable = true
        compact.isFocusable = true
        compact.setOnClickListener { onCollapse() }
        compact.ripple(0x22FFFFFF, 24)
        compact.show(false)

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        titleHandle.orientation = LinearLayout.HORIZONTAL
        titleHandle.gravity = Gravity.CENTER_VERTICAL
        val title = context.label("短视频挂机", 12f, Color.WHITE, true, 18f).apply { setSingleLine() }
        titleHandle.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(5) })
        status.setSingleLine()
        titleHandle.addView(status, LinearLayout.LayoutParams(-2, -2))
        header.addView(titleHandle, LinearLayout.LayoutParams(0, -1, 1f))
        val collapse = ImageView(context).apply {
            id = R.id.overlay_collapse
            setImageResource(R.drawable.ic_collapse)
            setColorFilter(Color.WHITE)
            setPadding(context.dp(13), context.dp(13), context.dp(13), context.dp(13))
            contentDescription = "收起悬浮窗"
            isClickable = true
            isFocusable = true
            setOnClickListener { onCollapse() }
            ripple(0x22FFFFFF, 22)
        }
        header.addView(collapse, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))

        counts.id = R.id.overlay_counts
        log.id = R.id.overlay_log
        log.maxLines = Int.MAX_VALUE
        log.ellipsize = null
        likeLog.id = R.id.overlay_like_log
        likeLog.setSingleLine()
        likeLog.ellipsize = TextUtils.TruncateAt.END
        logLines.orientation = LinearLayout.VERTICAL
        logLines.setPadding(0, 0, 0, context.dp(9))
        logLines.addView(log, LinearLayout.LayoutParams(-1, -2))
        logLines.addView(likeLog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(3) })
        logScroll.addView(logLines, FrameLayout.LayoutParams(-1, -2))
        logScroll.isFillViewport = false
        logScroll.isVerticalScrollBarEnabled = true

        controls.orientation = LinearLayout.HORIZONTAL
        action.id = R.id.overlay_action
        stop.id = R.id.overlay_stop
        action.setOnClickListener {
            onAction(if (current.status == RunStatus.PAUSED) RunControlAction.RESUME else RunControlAction.PAUSE)
        }
        stop.setOnClickListener { onAction(RunControlAction.STOP) }
        controls.addView(action, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginEnd = context.dp(6) })
        controls.addView(stop, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        stop.render("关闭", 0x14FFFFFF, Color.WHITE, R.drawable.ic_stop)

        expanded = OverlayStack(context, header, counts, logScroll, controls).apply {
            background = context.rounded(0x94000000.toInt(), 12)
            clipToOutline = true
        }
        addView(expanded, LayoutParams(-1, -2))
        addView(compact, LayoutParams(-1, -2))
    }

    fun render(state: RunState, collapsed: Boolean) {
        if (current == state && this.collapsed == collapsed) return
        current = state
        this.collapsed = collapsed
        compact.show(collapsed)
        expanded.show(!collapsed)
        compact.showText(state.compactLabel)
        compact.contentDescription = state.compactLabel + "，展开悬浮窗"
        status.showText(state.label)
        val session = state.session ?: return
        counts.update("${session.completed}/${session.config.swipeCount} 次",
            if (state.locked && session.stage != RunStage.STARTUP && session.dwellMs > 0)
                "停留 ${intervalSeconds(session.dwellMs)} 秒" else null)
        val latest = state.latestLog
        val newKey = session.id to latest?.message
        log.showText(logText(latest?.timeMillis, latest?.message ?: "等待任务"))
        likeLog.show(session.config.randomLike)
        if (session.config.randomLike) likeLog.showText(logText(session.likeCountUpdatedAtMillis, "已点赞 ${session.likeCount} 次"))
        if (logKey != newKey) {
            logKey = newKey
            logScroll.scrollTo(0, 0)
        }
        val paused = state.status == RunStatus.PAUSED
        action.show(state.status != RunStatus.COMPLETED)
        action.render(if (paused) "继续" else "暂停", 0x1FFFFFFF, Color.WHITE,
            if (paused) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private fun logText(time: Long?, message: String) =
        (time?.let { formatter.format(Date(it)) } ?: "—") + "  " + message

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(maximumHeight, parentLimit), MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onHandle("compact", if (collapsed) boundsOf(compact) else RectF())
        onHandle("header", if (collapsed) RectF() else boundsOf(titleHandle))
        onHandle("count", if (collapsed) RectF() else boundsOf(counts))
        val canScroll = logScroll.canScrollVertically(1) || logScroll.canScrollVertically(-1)
        onHandle("log", if (!collapsed && !canScroll) boundsOf(logScroll) else RectF())
        onHandle("scroll", if (!collapsed && canScroll) boundsOf(logScroll) else RectF())
    }

    private fun boundsOf(view: View): RectF {
        val bounds = Rect()
        view.getDrawingRect(bounds)
        offsetDescendantRectToMyCoords(view, bounds)
        return RectF(bounds)
    }
}

/** Measure fixed controls first so a long recovery guide cannot push buttons off screen. */
private class OverlayStack(
    context: Context,
    private val header: View,
    private val count: View,
    private val log: View,
    private val controls: View,
) : ViewGroup(context) {
    init {
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        addView(header)
        addView(count)
        addView(log)
        addView(controls)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val exactWidth = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY)
        val unlimited = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        header.measure(exactWidth, MeasureSpec.makeMeasureSpec(context.dp(44), MeasureSpec.EXACTLY))
        count.measure(exactWidth, unlimited)
        controls.measure(exactWidth, MeasureSpec.makeMeasureSpec(context.dp(44), MeasureSpec.EXACTLY))
        val fixed = paddingTop + paddingBottom + header.measuredHeight + count.measuredHeight + controls.measuredHeight
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        log.measure(exactWidth, MeasureSpec.makeMeasureSpec((limit - fixed).coerceAtLeast(0), MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(fixed + log.measuredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        for (child in arrayOf(header, count, log)) {
            child.layout(paddingLeft, y, width - paddingRight, y + child.measuredHeight)
            y += child.measuredHeight
        }
        val controlsTop = (height - paddingBottom - controls.measuredHeight).coerceAtLeast(0)
        controls.layout(paddingLeft, controlsTop, width - paddingRight, controlsTop + controls.measuredHeight)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}

/** The dwell value wraps as a unit when large fonts leave insufficient room beside the count. */
private class CountRow(context: Context) : ViewGroup(context) {
    private val count = context.label("", 12f, Color.WHITE, true, 18f)
    private val dwell = context.label("", 12f, Color.WHITE, false, 18f)
    private var wrapped = false

    init {
        count.setSingleLine()
        dwell.setSingleLine()
        count.ellipsize = TextUtils.TruncateAt.END
        dwell.ellipsize = TextUtils.TruncateAt.END
        addView(count)
        addView(dwell)
        setPadding(0, 0, 0, context.dp(5))
    }

    fun update(countText: String, dwellText: String?) {
        count.showText(countText)
        dwell.show(dwellText != null)
        dwell.showText(dwellText.orEmpty())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val atMost = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val unlimited = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        count.measure(atMost, unlimited)
        dwell.measure(atMost, unlimited)
        wrapped = dwell.visibility == VISIBLE && count.measuredWidth + dwell.measuredWidth > width
        val height = if (wrapped) count.measuredHeight + context.dp(2) + dwell.measuredHeight
            else maxOf(count.measuredHeight, if (dwell.visibility == VISIBLE) dwell.measuredHeight else 0)
        setMeasuredDimension(width, height + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        count.layout(0, 0, count.measuredWidth, count.measuredHeight)
        if (dwell.visibility == VISIBLE) {
            val y = if (wrapped) count.measuredHeight + context.dp(2) else 0
            val x = if (wrapped) 0 else width - dwell.measuredWidth
            dwell.layout(x, y, x + dwell.measuredWidth, y + dwell.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
