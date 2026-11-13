package com.shortvideo.autoswipe.overlay

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.model.*
import com.shortvideo.autoswipe.platform.ScreenGeometry
import com.shortvideo.autoswipe.ui.tap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadow.api.Shadow

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeOverlayWindowTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var window: OverlayWindow
    private lateinit var manager: WindowManager
    private val failures = mutableListOf<String>()
    private val run = RunState(RunStatus.RUNNING,
        RunSession("first", TaskConfig(), TargetApp("example.video", "example.video/.Main", "测试应用")),
        overlayVisible = true)

    @Before fun create() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val context = controller.get()
        manager = context.getSystemService(WindowManager::class.java)
        ShadowSettings.setCanDrawOverlays(true)
        window = OverlayWindow(context, ScreenGeometry(context), {}, { failures += it })
    }

    @After fun destroy() { window.close(); controller.pause().stop().destroy() }

    private fun hosts(): List<DragHost> = Shadow.extract<ShadowWindowManagerImpl>(manager).views.filterIsInstance<DragHost>()

    private fun update(state: RunState) {
        window.update(state)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun repeatedUpdatesReuseWindowAndKeepScreenOnOnlyWhileRunning() {
        update(run)
        assertTrue(failures.isEmpty())
        val host = hosts().single()
        assertNotNull(window.currentBounds())
        update(run.copy(latestLog = LogEntry(1, "状态更新")))
        assertSame(host, hosts().single())
        assertTrue((host.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        update(run.copy(status = RunStatus.PAUSED))
        assertEquals(0, (host.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        update(run.copy(status = RunStatus.COMPLETED))
        assertSame(host, hosts().single())
        window.close()
        assertTrue(hosts().isEmpty())
        assertNull(window.currentBounds())
    }

    @Test fun newTaskResetsCollapsedAppearanceAndStopRemovesWindow() {
        update(run)
        val old = hosts().single()
        tap(old, old.findViewById(R.id.overlay_collapse))
        assertEquals(View.VISIBLE, old.findViewById<View>(R.id.overlay_compact).visibility)
        update(run.copy(status = RunStatus.COMPLETED))
        update(run.copy(session = run.session!!.copy(id = "second")))
        val fresh = hosts().single()
        assertNotSame(old, fresh)
        assertEquals(View.GONE, fresh.findViewById<View>(R.id.overlay_compact).visibility)
        update(RunState(status = RunStatus.STOPPED))
        assertTrue(hosts().isEmpty())
    }

    @Test fun revokingPermissionClosesWindowAndReportsFailure() {
        update(run)
        ShadowSettings.setCanDrawOverlays(false)
        update(run)
        assertTrue(hosts().isEmpty())
        assertEquals(listOf("悬浮窗权限已关闭"), failures)
    }
}
