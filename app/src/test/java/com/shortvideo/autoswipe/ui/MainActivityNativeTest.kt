package com.shortvideo.autoswipe.ui

import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.shortvideo.autoswipe.MainActivity
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.appContainer
import com.shortvideo.autoswipe.model.RunStatus
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
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityNativeTest {
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun create() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        await { controller.get().findViewById<View>(R.id.input_app).isEnabled }
    }

    @After fun destroy() { controller.pause().stop().destroy() }

    @Test fun recreationKeepsSharedViewModelDraftAndInputSelection() {
        val activity = controller.get()
        val before = ViewModelProvider(activity)[MainViewModel::class.java]
        val input = activity.findViewById<DraftInput>(R.id.input_app)
        input.requestFocus()
        input.setText("测试应用")
        input.setSelection(2)
        activity.findViewById<DraftInput>(R.id.input_count).setText("321")
        activity.findViewById<LikeSwitch>(R.id.random_like).performClick()
        await { before.uiState.value.draft.randomLike && before.uiState.value.draft.count == "321" }
        controller.recreate()
        val after = controller.get()
        await { after.findViewById<DraftInput>(R.id.input_app).text.toString() == "测试应用" }
        assertSame(before, ViewModelProvider(after)[MainViewModel::class.java])
        assertEquals("321", after.findViewById<DraftInput>(R.id.input_count).text.toString())
        assertTrue(after.findViewById<LikeSwitch>(R.id.random_like).isChecked)
        assertEquals(2, after.findViewById<DraftInput>(R.id.input_app).selectionStart)
    }

    @Test fun permissionEntryAndResumeNeverStartTask() {
        val activity = controller.get()
        activity.findViewById<View>(R.id.primary_action).performClick()
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(RunStatus.IDLE, activity.appContainer.engine.state.value.status)
        controller.pause().stop().start().resume()
        assertEquals(RunStatus.IDLE, activity.appContainer.engine.state.value.status)
    }

    @Test fun titleOpensRepositoryInBrowserWithoutStartingTask() {
        val activity = controller.get()
        val screen = activity.findViewById<TaskScreen>(R.id.task_screen)
        layoutView(screen, 393, 800)
        tap(screen, activity.findViewById(R.id.repository_title))
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/kun520ovo/short-video-autoswipe", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.selector)
        assertEquals(RunStatus.IDLE, activity.appContainer.engine.state.value.status)
    }

    @Test fun unavailableBrowserStaysSilentAndKeepsTaskIdle() {
        val activity = controller.get()
        val application = shadowOf(activity.application)
        val messageBefore = activity.findViewById<TextView>(R.id.run_message).text.toString()
        application.checkActivities(true)
        try {
            activity.findViewById<View>(R.id.repository_title).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(messageBefore, activity.findViewById<TextView>(R.id.run_message).text.toString())
            assertFalse(activity.isFinishing)
            assertEquals(RunStatus.IDLE, activity.appContainer.engine.state.value.status)
        } finally {
            application.checkActivities(false)
        }
    }

    @Test fun browserWithWebHandlerButNoAppBrowserCategoryCanOpenRepository() {
        val activity = controller.get()
        val packages = shadowOf(activity.packageManager)
        val component = ComponentName("example.browser", "example.browser.Main")
        packages.addActivityIfNotPresent(component).apply { enabled = true; exported = true }
        packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kun520ovo/short-video-autoswipe"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        assertNotNull(activity.packageManager.resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY))
        assertNull(activity.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), PackageManager.MATCH_DEFAULT_ONLY))
        val application = shadowOf(activity.application)
        application.checkActivities(true)
        try {
            activity.findViewById<View>(R.id.repository_title).performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertNotNull("An installed HTTPS browser must receive the repository request", opened)
            // Android resolves the selector instead of the outer URL intent when one is present.
            val resolved = (opened.selector ?: opened).resolveActivity(activity.packageManager)
            assertNotNull("The effective launch request must match the installed HTTPS browser", resolved)
            assertEquals("example.browser", resolved?.packageName)
            assertEquals("example.browser", opened.`package`)
            assertEquals(web.data, opened.data)
        } finally {
            application.checkActivities(false)
        }
    }

    @Test fun systemWebHandlerIsUsedWhenNoBrowserMatchesGenericProbe() {
        val activity = controller.get()
        installWebHandler("example.webhandler", "github.com")
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        assertNull(activity.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY))
        assertSystemWebFallback("example.webhandler")
    }

    @Test fun domainRestrictedProbeHandlerDoesNotBlockRepositoryFallback() {
        installWebHandler("example.probehandler", "example.org")
        installWebHandler("example.webhandler", "github.com")
        assertSystemWebFallback("example.webhandler")
    }

    private fun installWebHandler(packageName: String, host: String) {
        val packages = shadowOf(controller.get().packageManager)
        val component = ComponentName(packageName, "$packageName.Main")
        packages.addActivityIfNotPresent(component).apply { enabled = true; exported = true }
        packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            addDataAuthority(host, null)
        })
    }

    private fun assertSystemWebFallback(packageName: String) {
        val activity = controller.get()
        val application = shadowOf(activity.application)
        application.checkActivities(true)
        try {
            activity.findViewById<View>(R.id.repository_title).performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertNotNull(opened)
            assertNull(opened.selector)
            assertNull(opened.`package`)
            assertEquals("https://github.com/kun520ovo/short-video-autoswipe", opened.dataString)
            assertEquals(packageName, opened.resolveActivity(activity.packageManager)?.packageName)
            assertEquals(RunStatus.IDLE, activity.appContainer.engine.state.value.status)
        } finally {
            application.checkActivities(false)
        }
    }

    @Test fun stateCollectionStopsWithActivityAndResumesWithLatestMessage() {
        val activity = controller.get()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        controller.pause().stop()
        model.message("测试恢复提示")
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals("测试恢复提示", activity.findViewById<TextView>(R.id.run_message).text.toString())
        controller.start().resume()
        await { activity.findViewById<TextView>(R.id.run_message).text.toString() == "测试恢复提示" }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        }
        assertTrue("Timed out waiting for the real ViewModel/DataStore state", condition())
    }
}
