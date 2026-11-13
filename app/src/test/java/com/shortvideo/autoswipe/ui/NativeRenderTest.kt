package com.shortvideo.autoswipe.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.domain.AppMatch
import com.shortvideo.autoswipe.domain.GESTURE_RECOVERY_GUIDE
import com.shortvideo.autoswipe.model.*
import com.shortvideo.autoswipe.platform.PermissionState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Native Android raster output is retained for human review, not used as a fragile pixel golden. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeRenderTest {
    private val app = TargetApp("com.example.video", "com.example.video/.Main", "抖音极速版")
    private val ready get() = MainUiState(ready = true, appLoading = false,
        permissions = PermissionState(true, true, true), match = AppMatch(listOf(app), app))
    private val callbacks = TaskScreenActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }, {})

    @Test fun renderLightSettings() = renderMain("settings-light", ready)

    @Test fun renderDarkSettingsWithLikeProbability() {
        RuntimeEnvironment.setQualifiers("+night")
        renderMain("settings-dark", ready.copy(draft = ready.draft.copy(randomLike = true)))
    }

    @Test fun renderNarrowSettingsWithLargeFonts() {
        RuntimeEnvironment.setQualifiers("w320dp-h720dp-xhdpi")
        RuntimeEnvironment.setFontScale(1.5f)
        renderMain("settings-large-font", ready.copy(draft = ready.draft.copy(randomLike = true)), width = 320)
    }

    @Test fun renderRecoveryGuideAndPauseTypography() {
        val run = RunState(RunStatus.PAUSED, RunSession("task", TaskConfig(), app), pauseReason = GESTURE_RECOVERY_GUIDE)
        renderMain("settings-recovery", ready.copy(run = run, message = GESTURE_RECOVERY_GUIDE))
    }

    @Test fun renderApplicationPicker() {
        val other = app.copy(component = "com.example.video/.Alternate", label = "抖音极速版（另一个入口）")
        renderMain("settings-picker", ready.copy(picker = true, match = AppMatch(listOf(app, other), null)), capturePicker = true)
    }

    @Test fun renderExpandedCollapsedAndConstrainedOverlays() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val content = OverlayContent(activity, {}, {}, { _, _ -> })
            val run = RunState(RunStatus.RUNNING, RunSession("task", TaskConfig(), app,
                completed = 12, stage = RunStage.WAIT_BEFORE_LIKE, dwellMs = 7350),
                latestLog = LogEntry(0, "第 12 次上滑完成"), overlayVisible = true)
            for ((name, state, collapsed, height) in listOf(
                OverlayCase("overlay-running", run, false, 800),
                OverlayCase("overlay-collapsed", run, true, 800),
                OverlayCase("overlay-recovery", run.copy(status = RunStatus.PAUSED, latestLog = LogEntry(0, GESTURE_RECOVERY_GUIDE)), false, 180),
                OverlayCase("overlay-likes", run.copy(session = run.session!!.copy(config = TaskConfig(randomLike = true), likeCount = 3)), false, 800),
                OverlayCase("overlay-completed", run.copy(status = RunStatus.COMPLETED), false, 800),
            )) {
                content.render(state, collapsed)
                content.maximumHeight = activity.dp(height)
                content.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(190), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(activity.dp(height), View.MeasureSpec.AT_MOST))
                content.layout(0, 0, content.measuredWidth, content.measuredHeight)
                save(content, name, 0xFF383838.toInt())
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun renderMain(name: String, ui: MainUiState, width: Int = 393, capturePicker: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_ShortVideoAutoSwipe)
        controller.setup()
        val screen = TaskScreen(activity, callbacks)
        try {
            activity.setContentView(screen)
            screen.render(ui)
            layoutView(screen, width, 800)
            save(screen.getChildAt(0), name, activity.appPalette().background)
            if (capturePicker) {
                settleClicks()
                val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                decor.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width - 48), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(activity.dp(600), View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                save(decor, "app-picker", activity.appPalette().background)
            }
        } finally { screen.dispose(); controller.pause().stop().destroy() }
    }

    private fun save(view: View, name: String, background: Int) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(background)
        view.draw(Canvas(bitmap))
        val output = File("build/reports/native-ui/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private data class OverlayCase(val name: String, val state: RunState, val collapsed: Boolean, val height: Int)
}
