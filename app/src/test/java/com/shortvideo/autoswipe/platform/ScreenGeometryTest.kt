package com.shortvideo.autoswipe.platform

import android.app.Activity
import android.graphics.Insets
import android.graphics.Rect
import android.os.Looper
import android.view.DisplayCutout
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.shortvideo.autoswipe.domain.PixelRect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Suppress("DEPRECATION")
class ScreenGeometryTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var manager: WindowManager
    private lateinit var geometry: ScreenGeometry
    private var changes = 0

    @Before fun create() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        manager = controller.get().getSystemService(WindowManager::class.java)
        ShadowSettings.setCanDrawOverlays(true)
        geometry = ScreenGeometry(controller.get())
        display(1080, 1920, Surface.ROTATION_0)
    }

    @After fun destroy() { geometry.stopObserving(); controller.pause().stop().destroy() }

    @Test fun screenAreaIsUnknownUntilInsetsArrive() {
        assertNull(geometry.usableBounds())
        assertEquals(PixelRect(0f, 0f, 1080f, 1920f), geometry.displayBounds())
    }

    @Test fun portraitUsesActualTopAndBottomInsetsAndReaderCannotInterceptInput() {
        val view = observe(1080, 1920)
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(0f, params.alpha, 0f)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        publish(view, Insets.of(0, 32, 0, 120))
        assertEquals(PixelRect(0f, 32f, 1080f, 1800f), geometry.usableBounds())
        val before = changes
        publish(view, Insets.of(0, 32, 0, 120))
        assertEquals(before, changes)
    }

    @Test fun landscapeHandlesNavigationOnEitherSide() {
        display(1920, 1080, Surface.ROTATION_90)
        val view = observe(1920, 1080)
        publish(view, Insets.of(0, 32, 120, 0))
        assertEquals(PixelRect(0f, 32f, 1800f, 1080f), geometry.usableBounds())
        display(1920, 1080, Surface.ROTATION_270)
        assertNull(geometry.usableBounds())
        publish(view, Insets.of(120, 32, 0, 0))
        assertEquals(PixelRect(120f, 32f, 1920f, 1080f), geometry.usableBounds())
    }

    @Test fun cutoutAndHiddenSystemBarsUseTheMaximumInsetOnEachEdge() {
        val view = observe(1080, 1920)
        val cutout = DisplayCutout(Rect(80, 60, 0, 0), listOf(Rect(0, 0, 80, 60)))
        view.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setSystemWindowInsets(Insets.NONE)
            .setStableInsets(Insets.of(0, 32, 0, 120))
            .setDisplayCutout(cutout).build())
        assertEquals(PixelRect(80f, 60f, 1080f, 1800f), geometry.usableBounds())
    }

    @Test fun rotationWaitsForNewInsetsAndLayoutInsteadOfUsingOldBounds() {
        val view = observe(1080, 1920)
        publish(view, Insets.of(0, 32, 0, 120))
        assertNotNull(geometry.usableBounds())
        display(1920, 1080, Surface.ROTATION_90)
        assertNull(geometry.usableBounds())
        publish(view, Insets.of(0, 32, 120, 0))
        assertNull(geometry.usableBounds())
        layout(view, 1920, 1080)
        assertEquals(PixelRect(0f, 32f, 1800f, 1080f), geometry.usableBounds())
    }

    @Test fun closingReaderRemovesItAndLateEventsCannotRestoreOldBounds() {
        val old = observe(1080, 1920)
        publish(old, Insets.of(0, 32, 0, 120))
        geometry.stopObserving()
        assertTrue(readers().isEmpty())
        assertNull(geometry.usableBounds())
        val before = changes
        publish(old, Insets.of(100, 100, 100, 100))
        assertEquals(before, changes)
        assertNull(geometry.usableBounds())
        val fresh = observe(1080, 1920)
        assertNotSame(old, fresh)
        publish(fresh, Insets.of(0, 24, 0, 30))
        assertEquals(PixelRect(0f, 24f, 1080f, 1890f), geometry.usableBounds())
    }

    @Test @Config(sdk = [35]) fun modernAndroidUsesWindowMetricsWithoutAnExtraWindow() {
        geometry.observeInsets { changes++ }
        assertTrue(readers().isEmpty())
        assertNotNull(geometry.usableBounds())
    }

    private fun readers() = Shadow.extract<ShadowWindowManagerImpl>(manager).views.filter {
        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    private fun observe(width: Int, height: Int): View {
        geometry.observeInsets { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        return readers().single().also { layout(it, width, height) }
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun publish(view: View, insets: Insets) {
        view.dispatchApplyWindowInsets(WindowInsets.Builder().setSystemWindowInsets(insets).setStableInsets(insets).build())
    }

    private fun display(width: Int, height: Int, rotation: Int) {
        shadowOf(manager.defaultDisplay).apply { setRealWidth(width); setRealHeight(height); setRotation(rotation) }
    }
}
