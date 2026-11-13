package com.shortvideo.autoswipe.ui

import android.app.Activity
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.domain.GESTURE_RECOVERY_GUIDE
import com.shortvideo.autoswipe.model.*
import com.shortvideo.autoswipe.overlay.DragHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeOverlayTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var host: DragHost
    private lateinit var content: OverlayContent
    private val actions = mutableListOf<RunControlAction>()
    private val movement = mutableListOf<Pair<Float, Float>>()
    private var dragStarted = 0
    private var touching = false
    private var collapsed = false
    private var state = RunState(RunStatus.RUNNING, RunSession("task", TaskConfig(),
        TargetApp("example.video", "example.video/.Main", "抖音极速版"), completed = 12,
        stage = RunStage.WAIT_BEFORE_LIKE, dwellMs = 7350), latestLog = LogEntry(0, "第 12 次上滑完成"), overlayVisible = true)

    @Before fun create() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        host = DragHost(activity, { dragStarted++ }, { x, y -> movement += x to y }, { touching = it })
        content = OverlayContent(activity, {
            collapsed = !collapsed
            host.handles.clear()
            content.render(state, collapsed)
            measure()
        }, { actions += it }, { key, bounds ->
            if (key == "scroll") host.scrollRegion.set(bounds) else host.handles[key] = bounds
        })
        host.addView(content, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(FrameLayout(activity).apply {
            addView(host, FrameLayout.LayoutParams(activity.dp(190), -2))
        })
        content.render(state, false)
        measure()
    }

    @After fun destroy() { controller.pause().stop().destroy() }

    private fun measure(width: Int = 190, height: Int = 800) {
        content.maximumHeight = host.context.dp(height)
        host.measure(View.MeasureSpec.makeMeasureSpec(host.context.dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(host.context.dp(height), View.MeasureSpec.AT_MOST))
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    private fun point(rect: RectF): Pair<Float, Float> {
        val location = IntArray(2)
        host.getLocationOnScreen(location)
        return location[0] + rect.centerX() to location[1] + rect.centerY()
    }

    @Test fun buttonsControlCurrentStateAndCompletionKeepsStop() {
        tap(host, host.findViewById(R.id.overlay_action))
        state = state.copy(status = RunStatus.PAUSED)
        content.render(state, false)
        tap(host, host.findViewById(R.id.overlay_action))
        tap(host, host.findViewById(R.id.overlay_stop))
        assertEquals(listOf(RunControlAction.PAUSE, RunControlAction.RESUME, RunControlAction.STOP), actions)
        state = state.copy(status = RunStatus.COMPLETED)
        content.render(state, false)
        measure()
        assertEquals(View.GONE, host.findViewById<View>(R.id.overlay_action).visibility)
        val stop = host.findViewById<View>(R.id.overlay_stop)
        assertEquals((stop.parent as View).width, stop.width)
        assertFalse(touching)
    }

    @Test fun collapsingAndExpandingReusesViewsAndPreservesCounts() {
        val log = host.findViewById<TextView>(R.id.overlay_log)
        repeat(12) {
            tap(host, host.findViewById(R.id.overlay_collapse))
            assertTrue(collapsed)
            assertTrue(host.findViewById<TextView>(R.id.overlay_compact).text.contains("12/2000"))
            tap(host, host.findViewById(R.id.overlay_compact))
            assertFalse(collapsed)
            assertSame(log, host.findViewById<TextView>(R.id.overlay_log))
        }
        assertTrue(actions.isEmpty())
    }

    @Test fun completeRecoveryGuideWrapsAndScrollsWhileButtonsRemainVisible() {
        state = state.copy(status = RunStatus.PAUSED, latestLog = LogEntry(1000, GESTURE_RECOVERY_GUIDE))
        content.render(state, false)
        measure(height = 160)
        val log = host.findViewById<TextView>(R.id.overlay_log)
        assertTrue(log.text.endsWith(GESTURE_RECOVERY_GUIDE))
        assertTrue(log.lineCount > 1)
        assertFalse(host.scrollRegion.isEmpty)
        assertTrue(host.handles.getValue("log").isEmpty)
        val stop = host.findViewById<View>(R.id.overlay_stop)
        val rect = android.graphics.Rect()
        stop.getDrawingRect(rect)
        host.offsetDescendantRectToMyCoords(stop, rect)
        assertTrue(rect.bottom <= host.height)
        assertEquals(host.context.dp(44), stop.height)
    }

    @Test fun scrollableGuideDoesNotDragTheWindow() {
        state = state.copy(latestLog = LogEntry(1000, GESTURE_RECOVERY_GUIDE.repeat(2)))
        content.render(state, false)
        measure(height = 180)
        val (x, y) = point(host.scrollRegion)
        touch(host, MotionEvent.ACTION_DOWN, x, y)
        touch(host, MotionEvent.ACTION_MOVE, x, y - host.context.dp(20))
        touch(host, MotionEvent.ACTION_MOVE, x, y - host.context.dp(40))
        touch(host, MotionEvent.ACTION_UP, x, y - host.context.dp(40))
        assertTrue(movement.isEmpty())
        assertTrue(actions.isEmpty())
        assertFalse(touching)
    }

    @Test fun newLogResetsScrollButCountUpdatesPreserveReadingPosition() {
        state = state.copy(latestLog = LogEntry(1000, GESTURE_RECOVERY_GUIDE.repeat(2)))
        content.render(state, false)
        measure(height = 180)
        val log = host.findViewById<View>(R.id.overlay_log)
        val scroll = log.parent.parent as ScrollView
        scroll.scrollTo(0, host.context.dp(20))
        assertTrue(scroll.scrollY > 0)
        state = state.copy(session = state.session!!.copy(completed = 13))
        content.render(state, false)
        assertTrue(scroll.scrollY > 0)
        state = state.copy(latestLog = LogEntry(2000, "切回应用后，请继续"))
        content.render(state, false)
        assertEquals(0, scroll.scrollY)
    }

    @Test fun likeCountVisibilityFollowsTaskConfiguration() {
        assertEquals(View.GONE, host.findViewById<View>(R.id.overlay_like_log).visibility)
        state = state.copy(session = state.session!!.copy(config = TaskConfig(randomLike = true), likeCount = 3))
        content.render(state, false)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.overlay_like_log).visibility)
        assertTrue(host.findViewById<TextView>(R.id.overlay_like_log).text.endsWith("已点赞 3 次"))
        state = state.copy(session = state.session!!.copy(config = TaskConfig(randomLike = false)))
        content.render(state, false)
        assertEquals(View.GONE, host.findViewById<View>(R.id.overlay_like_log).visibility)
    }

    @Test fun headerDragUsesRawCoordinatesAndNeverClicks() {
        val (x, y) = point(host.handles.getValue("header"))
        touch(host, MotionEvent.ACTION_DOWN, x, y)
        touch(host, MotionEvent.ACTION_MOVE, x + host.context.dp(30), y + host.context.dp(20))
        touch(host, MotionEvent.ACTION_UP, x + host.context.dp(30), y + host.context.dp(20))
        assertEquals(1, dragStarted)
        assertTrue(movement.isNotEmpty())
        assertEquals(host.context.dp(30).toFloat(), movement.last().first, .1f)
        assertTrue(actions.isEmpty())
        assertFalse(touching)
    }

    @Test fun movementWithinButtonBeyondSlopCancelsPendingClick() {
        val button = host.findViewById<View>(R.id.overlay_action)
        val (x, y) = centerOnScreen(button)
        touch(host, MotionEvent.ACTION_DOWN, x, y)
        touch(host, MotionEvent.ACTION_MOVE, x + host.context.dp(20), y)
        touch(host, MotionEvent.ACTION_UP, x + host.context.dp(20), y)
        settleClicks()
        assertTrue(actions.isEmpty())
        assertTrue(movement.isEmpty())
        assertFalse(touching)
    }

    @Test fun cancelledButtonPressDoesNotExecuteAction() {
        val (x, y) = centerOnScreen(host.findViewById(R.id.overlay_action))
        touch(host, MotionEvent.ACTION_DOWN, x, y)
        touch(host, MotionEvent.ACTION_CANCEL, x, y)
        settleClicks()
        assertTrue(actions.isEmpty())
        assertFalse(touching)
    }

    @Test fun multiTouchCancelsButtonUntilLastPointerIsReleased() {
        val (x, y) = centerOnScreen(host.findViewById(R.id.overlay_action))
        touch(host, MotionEvent.ACTION_DOWN, x, y)
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply { this.x = x + index; this.y = y; pressure = 1f; size = 1f } }
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
            val location = IntArray(2)
            host.getLocationOnScreen(location)
            it.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
            host.dispatchTouchEvent(it)
            it.recycle()
        }
        assertTrue(touching)
        touch(host, MotionEvent.ACTION_UP, x, y)
        settleClicks()
        assertFalse(touching)
        assertTrue(actions.isEmpty())
    }
}
