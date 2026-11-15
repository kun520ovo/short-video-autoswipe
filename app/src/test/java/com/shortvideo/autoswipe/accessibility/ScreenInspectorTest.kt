package com.shortvideo.autoswipe.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shortvideo.autoswipe.domain.BlockKind
import com.shortvideo.autoswipe.domain.SafetyCheck
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.platform.PermissionChecker
import com.shortvideo.autoswipe.platform.ScreenGeometry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@Suppress("DEPRECATION")
class ScreenInspectorTest {
    private lateinit var controller: ServiceController<SwipeAccessibilityService>
    private lateinit var service: SwipeAccessibilityService
    private lateinit var geometry: ScreenGeometry
    private lateinit var inspector: ScreenInspector
    private val target = TargetApp("example.video", "example.video/.Main", "测试应用")

    @Before fun create() {
        controller = Robolectric.buildService(SwipeAccessibilityService::class.java).create()
        service = controller.get()
        val enabledService = AccessibilityServiceInfo().apply { feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC }
        ReflectionHelpers.setField(enabledService, "mResolveInfo", ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = service.packageName
                name = SwipeAccessibilityService::class.java.name
            }
        })
        shadowOf(service.getSystemService(AccessibilityManager::class.java)).setEnabledAccessibilityServiceList(listOf(enabledService))
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(service.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val packages = shadowOf(service.packageManager)
        val component = ComponentName.unflattenFromString(target.component)!!
        packages.addActivityIfNotPresent(component).apply {
            enabled = true
            exported = true
            requireNotNull(applicationInfo).apply { enabled = true; nonLocalizedLabel = target.label }
        }
        packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        val context = if (Build.VERSION.SDK_INT >= 30) {
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else service
        geometry = ScreenGeometry(context)
        geometry.observeInsets {}
        shadowOf(Looper.getMainLooper()).idle()
        if (Build.VERSION.SDK_INT == 29) {
            val manager = context.getSystemService(WindowManager::class.java)
            val view = Shadow.extract<ShadowWindowManagerImpl>(manager).views.single()
            val metrics = DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
            view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            view.dispatchApplyWindowInsets(WindowInsets.Builder().setStableInsets(Insets.of(0, 24, 0, 48)).build())
        }
        inspector = ScreenInspector(service, geometry, PermissionChecker(service))
        assertEquals(SafetyCheck.Ready, inspector.availability())
        assertNotNull(geometry.usableBounds())
    }

    @After fun destroy() {
        if (::geometry.isInitialized) geometry.stopObserving()
        controller.destroy()
    }

    @Test fun focusedSystemPanelBlocksTheApplicationAndManualContinuation() {
        windows(window(1, AccessibilityWindowInfo.TYPE_SYSTEM, "com.android.systemui", focused = true), app(focused = false))
        val blocked = inspector.inspect(target).safety as SafetyCheck.Blocked
        assertEquals(BlockKind.GEOMETRY, blocked.kind)
        assertEquals("系统面板遮挡了应用，收起后自动继续", blocked.reason)
        // Framework queries return fresh snapshots; the Robolectric shadow retains its list.
        windows(window(1, AccessibilityWindowInfo.TYPE_SYSTEM, "com.android.systemui", focused = true), app(focused = false))
        assertNull(inspector.foregroundTarget())
    }

    @Test fun activeSystemPanelBlocksEvenWhileApplicationRetainsFocus() {
        windows(window(1, AccessibilityWindowInfo.TYPE_SYSTEM, "com.android.systemui", active = true), app())
        assertTrue(inspector.inspect(target).safety is SafetyCheck.Blocked)
    }

    @Test fun unreadableSystemPanelStillBlocksAndDismissalRestoresTheTarget() {
        windows(window(1, AccessibilityWindowInfo.TYPE_SYSTEM, null, focused = true), app(focused = false))
        assertTrue(inspector.inspect(target).safety is SafetyCheck.Blocked)
        windows(app())
        assertEquals(SafetyCheck.Ready, inspector.inspect(target).safety)
        windows(app())
        assertEquals(target.packageName, inspector.foregroundTarget()?.packageName)
    }

    @Test fun idleSystemBarsKeyboardAndOurControlsDoNotReplaceTheApplication() {
        fun showWindows() = windows(
            window(1, AccessibilityWindowInfo.TYPE_SYSTEM, "com.android.systemui"),
            window(3, AccessibilityWindowInfo.TYPE_INPUT_METHOD, "example.keyboard", active = true),
            window(4, AccessibilityWindowInfo.TYPE_SYSTEM, service.packageName, active = true),
            app(),
        )
        showWindows()
        assertEquals(SafetyCheck.Ready, inspector.inspect(target).safety)
        showWindows()
        assertEquals(target.packageName, inspector.foregroundTarget()?.packageName)
    }

    @Test fun accessibilityOverlayDoesNotReplaceTheApplication() {
        windows(window(1, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "example.accessibility", active = true), app())
        assertEquals(SafetyCheck.Ready, inspector.inspect(target).safety)
    }

    @Test @Config(sdk = [29]) fun noConfirmedScreenInsetsBlocksGestures() {
        geometry.stopObserving()
        windows(app())
        val blocked = inspector.inspect(target).safety as SafetyCheck.Blocked
        assertEquals(BlockKind.GEOMETRY, blocked.kind)
        assertEquals("正在确认屏幕可用区域", blocked.reason)
    }

    private fun app(focused: Boolean = true) = window(2, AccessibilityWindowInfo.TYPE_APPLICATION, target.packageName, focused)
    private fun windows(vararg windows: AccessibilityWindowInfo) { shadowOf(service).setWindows(windows.toList()) }

    private fun window(id: Int, type: Int, packageName: String?, focused: Boolean = false,
        active: Boolean = focused): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).apply {
            setId(id)
            setType(type)
            setFocused(focused)
            setActive(active)
            setLayer(if (type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1)
            setBoundsInScreen(Rect(0, 0, 1080, 1920))
            if (packageName != null) setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
        }
    }
}
