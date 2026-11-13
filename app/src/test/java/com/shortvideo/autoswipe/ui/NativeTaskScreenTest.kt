package com.shortvideo.autoswipe.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.ListView
import android.widget.TextView
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.domain.AppMatch
import com.shortvideo.autoswipe.domain.GESTURE_RECOVERY_GUIDE
import com.shortvideo.autoswipe.model.*
import com.shortvideo.autoswipe.platform.PermissionKind
import com.shortvideo.autoswipe.platform.PermissionState
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
import org.robolectric.shadows.ShadowDialog

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeTaskScreenTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var screen: TaskScreen
    private val target = TargetApp("example.video", "example.video/.Main", "抖音极速版")
    private val edits = mutableListOf<Pair<String, String>>()
    private val likes = mutableListOf<Boolean>()
    private val permissions = mutableListOf<PermissionKind>()
    private val selections = mutableListOf<TargetApp>()
    private var started = 0
    private var granted = 0
    private var paused = 0
    private var stopped = 0
    private var pickerClosed = 0
    private val ready get() = MainUiState(ready = true, appLoading = false,
        permissions = PermissionState(true, true, true), match = AppMatch(listOf(target), target))

    @Before fun create() {
        controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_ShortVideoAutoSwipe)
        controller.setup()
        screen = TaskScreen(activity, TaskScreenActions(
            edit = { field, value -> edits += field to value }, touched = {},
            setLike = { likes += it }, openPicker = {}, closePicker = { pickerClosed++ },
            selectApp = { selections += it }, start = { started++ }, pause = { paused++ },
            stop = { stopped++ }, allPermissions = { granted++ }, permission = { permissions += it },
            loadIcon = { null },
            openRepository = {},
        ))
        activity.setContentView(screen)
        screen.render(ready)
        layoutView(screen, 393, 800)
    }

    @After fun destroy() {
        screen.dispose()
        controller.pause().stop().destroy()
    }

    @Test fun likeSwitchHandlesRealTapsInBothDirections() {
        val toggle = screen.findViewById<LikeSwitch>(R.id.random_like)
        tap(screen, toggle)
        assertEquals(listOf(true), likes)
        screen.render(ready.copy(draft = ready.draft.copy(randomLike = true)))
        layoutView(screen, 393, 800)
        assertTrue(screen.findViewById<View>(R.id.input_like).isShown)
        tap(screen, toggle)
        assertEquals(listOf(true, false), likes)
    }

    @Test fun renderingDoesNotEmitEditsOrToggleEvents() {
        screen.render(ready.copy(draft = FormDraft(count = "123", minSeconds = "3", maxSeconds = "4", randomLike = true)))
        assertTrue(edits.isEmpty())
        assertTrue(likes.isEmpty())
        assertEquals("123", screen.findViewById<DraftInput>(R.id.input_count).text.toString())
    }

    @Test fun dwellInputsHaveVisibleMeasuredText() {
        for ((id, expected) in listOf(R.id.input_min to "5", R.id.input_max to "10")) {
            val input = screen.findViewById<DraftInput>(id)
            val description = "value=${input.text}, size=${input.width}x${input.height}, scroll=${input.scrollX},${input.scrollY}, layout=${input.layout?.width}x${input.layout?.height}"
            assertEquals(expected, input.text.toString())
            assertTrue(description, input.width > 0 && input.height >= input.lineHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(input.width, input.height, android.graphics.Bitmap.Config.ARGB_8888)
            input.draw(android.graphics.Canvas(bitmap))
            val pixels = IntArray(input.width * input.height)
            bitmap.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            assertTrue(description, pixels.count { android.graphics.Color.alpha(it) > 0 } > 10)
            bitmap.recycle()
        }
    }

    @Test fun unrelatedStateUpdatePreservesChineseCompositionCursorAndEditable() {
        val input = screen.findViewById<DraftInput>(R.id.input_app)
        input.requestFocus()
        input.setText("测试应用")
        input.setSelection(2)
        requireNotNull(input.onCreateInputConnection(EditorInfo())).setComposingRegion(0, 4)
        assertEquals(0, BaseInputConnection.getComposingSpanStart(input.text))
        val editable = input.text
        screen.render(ready.copy(draft = ready.draft.copy(appName = "测试应用"), message = "服务连接中"))
        assertSame(editable, input.text)
        assertEquals(2, input.selectionStart)
        assertEquals(0, BaseInputConnection.getComposingSpanStart(input.text))
        assertEquals(4, BaseInputConnection.getComposingSpanEnd(input.text))
        assertEquals(listOf("app" to "测试应用"), edits)
    }

    @Test fun inputTapKeepsFocusWhenKeyboardLayoutAndInputScrollMoveBetweenDownAndUp() {
        val input = screen.findViewById<DraftInput>(R.id.input_app)
        input.requestFocus()
        input.scrollTo(screen.context.dp(40), 0)
        val (x, y) = centerOnScreen(input)
        touch(screen, MotionEvent.ACTION_DOWN, x, y)
        layoutView(screen, 393, 300)
        screen.scrollTo(0, screen.context.dp(20))
        touch(screen, MotionEvent.ACTION_UP, x, y)
        settleClicks()
        assertTrue(input.hasFocus())
    }

    @Test fun blankTapDismissesFocusWhileScrollAndCancelDoNot() {
        val input = screen.findViewById<DraftInput>(R.id.input_app)
        input.requestFocus()
        val label = (screen.getChildAt(0) as android.view.ViewGroup).getChildAt(1)
        val (x, y) = centerOnScreen(label)
        touch(screen, MotionEvent.ACTION_DOWN, x, y)
        touch(screen, MotionEvent.ACTION_MOVE, x + screen.context.dp(30), y)
        touch(screen, MotionEvent.ACTION_UP, x + screen.context.dp(30), y)
        assertTrue(input.hasFocus())
        touch(screen, MotionEvent.ACTION_DOWN, x, y)
        touch(screen, MotionEvent.ACTION_CANCEL, x, y)
        assertTrue(input.hasFocus())
        tap(screen, label)
        assertFalse(input.hasFocus())
    }

    @Test fun doneActionAndSavedFocusUseCurrentDraft() {
        val input = screen.findViewById<DraftInput>(R.id.input_count)
        input.requestFocus()
        input.setSelection(2)
        val saved = Bundle()
        screen.saveEditingState(saved)
        input.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertFalse(input.hasFocus())
        screen.restoreEditingState(saved)
        screen.render(ready.copy(message = "状态更新"))
        assertTrue(input.hasFocus())
        assertEquals(2, input.selectionStart)
    }

    @Test fun runningAndPausedTasksLockInputsButKeepPermissionsAndStopAvailable() {
        val run = RunState(RunStatus.RUNNING, RunSession("task", TaskConfig(), target), overlayVisible = true)
        screen.render(ready.copy(run = run))
        layoutView(screen, 393, 800)
        assertFalse(screen.findViewById<DraftInput>(R.id.input_app).isEnabled)
        assertFalse(screen.findViewById<LikeSwitch>(R.id.random_like).isEnabled)
        tap(screen, screen.findViewById(R.id.random_like))
        assertTrue(likes.isEmpty())
        screen.findViewById<View>(R.id.pause_action).performClick()
        assertEquals(1, paused)
        screen.render(ready.copy(run = run.copy(status = RunStatus.PAUSED, pauseReason = "任务已暂停"), message = "任务已暂停"))
        assertEquals(View.GONE, screen.findViewById<View>(R.id.pause_action).visibility)
        screen.findViewById<View>(R.id.stop_action).performClick()
        screen.findViewById<View>(R.id.permission_accessibility).performClick()
        screen.findViewById<View>(R.id.permission_overlay).performClick()
        assertEquals(1, stopped)
        assertEquals(listOf(PermissionKind.ACCESSIBILITY, PermissionKind.OVERLAY), permissions)
        screen.render(ready)
        assertTrue(screen.findViewById<DraftInput>(R.id.input_app).isEnabled)
    }

    @Test fun recoveryGuideIsCompleteAndDoesNotDuplicateGenericContinueHint() {
        val run = RunState(RunStatus.PAUSED, RunSession("task", TaskConfig(), target), pauseReason = GESTURE_RECOVERY_GUIDE)
        screen.render(ready.copy(run = run, message = GESTURE_RECOVERY_GUIDE))
        assertEquals(GESTURE_RECOVERY_GUIDE, screen.findViewById<TextView>(R.id.run_message).text.toString())
        assertEquals(View.GONE, screen.findViewById<View>(R.id.continue_hint).visibility)
        screen.render(ready.copy(run = run.copy(pauseReason = "任务已暂停"), message = "任务已暂停"))
        assertTrue(screen.findViewById<TextView>(R.id.run_message).typeface.isBold)
        val hint = screen.findViewById<TextView>(R.id.continue_hint).text as Spanned
        val spans = hint.getSpans(0, hint.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.BOLD, spans.single().style)
        assertEquals("“继续”", hint.subSequence(hint.getSpanStart(spans.single()), hint.getSpanEnd(spans.single())).toString())
    }

    @Test fun primaryRoutesToPermissionsOrStartAndWaitsForServiceConnection() {
        screen.render(ready.copy(permissions = PermissionState(false, false, false)))
        screen.findViewById<View>(R.id.primary_action).performClick()
        assertEquals(1, granted)
        assertEquals(0, started)
        screen.render(ready.copy(permissions = PermissionState(true, true, false)))
        assertFalse(screen.findViewById<View>(R.id.primary_action).isEnabled)
        screen.render(ready)
        screen.findViewById<View>(R.id.primary_action).performClick()
        assertEquals(1, started)
    }

    @Test fun lookupFailureAndStartFeedbackRemainSeparateAndClearAfterRecovery() {
        val lookupError = "无法读取已安装应用，请稍后重试。"
        screen.render(ready.copy(appLoadError = lookupError, match = AppMatch(emptyList(), null), message = "请先匹配目标应用"))
        assertEquals(lookupError, screen.findViewWithTag<TextView>("error_app").text.toString())
        assertEquals("请先匹配目标应用", screen.findViewById<TextView>(R.id.run_message).text.toString())
        screen.render(ready)
        assertEquals(View.GONE, screen.findViewWithTag<View>("error_app").visibility)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.run_message).visibility)
    }

    @Test fun pickerSelectsDistinctEntrypointsAndClosesWithStateOrCancellation() {
        val second = target.copy(component = "example.video/.Other")
        val ui = ready.copy(picker = true, match = AppMatch(listOf(target, second), null))
        screen.render(ui)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val list = dialog.findViewById<ListView>(R.id.picker_list)
        assertEquals(2, list.adapter.count)
        list.performItemClick(list.adapter.getView(1, null, list), 1, 1)
        assertEquals(listOf(second), selections)
        screen.render(ready)
        assertFalse(dialog.isShowing)
        screen.render(ui)
        ShadowDialog.getLatestDialog().cancel()
        settleClicks()
        assertEquals(1, pickerClosed)
    }

    @Test fun smallScreenScrollsToControlsAndLargeNamesStayInsideForm() {
        screen.render(ready.copy(draft = ready.draft.copy(appName = "超长应用名称".repeat(10), randomLike = true)))
        layoutView(screen, 320, 320)
        assertTrue(screen.canScrollVertically(1))
        screen.fullScroll(View.FOCUS_DOWN)
        val primary = screen.findViewById<View>(R.id.primary_action)
        val visible = android.graphics.Rect()
        assertTrue(primary.getGlobalVisibleRect(visible))
        assertEquals(primary.height, visible.height())
    }
}
