package com.shortvideo.autoswipe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shortvideo.autoswipe.platform.PermissionKind
import com.shortvideo.autoswipe.ui.MainViewModel
import com.shortvideo.autoswipe.ui.TaskScreen
import com.shortvideo.autoswipe.ui.TaskScreenActions
import com.shortvideo.autoswipe.ui.appPalette

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    private lateinit var screen: TaskScreen
    private var permissionFlow = false
    private var pendingPermission: PermissionKind? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshPermissions()
        appContainer.bridge.refresh()
        val access = appContainer.permissionChecker.read(appContainer.bridge.connected.value)
        val granted = when (pendingPermission) {
            PermissionKind.ACCESSIBILITY -> access.accessibility
            PermissionKind.OVERLAY -> access.overlay
            null -> false
        }
        pendingPermission = null
        if (permissionFlow && granted) requestNextPermission()
        else permissionFlow = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionFlow = savedInstanceState?.getBoolean("permission_flow") ?: false
        pendingPermission = savedInstanceState?.getString("pending_permission")?.let {
            runCatching { PermissionKind.valueOf(it) }.getOrNull()
        }
        model = ViewModelProvider(this, viewModelFactory {
            initializer { MainViewModel(this@MainActivity.appContainer) }
        })[MainViewModel::class.java]
        screen = TaskScreen(this, TaskScreenActions(
            edit = model::edit,
            touched = model::touched,
            setLike = model::setLike,
            openPicker = model::openPicker,
            closePicker = model::closePicker,
            selectApp = model::selectApp,
            start = { model.start(this) },
            pause = model::pause,
            stop = model::stop,
            allPermissions = { permissionFlow = true; requestNextPermission() },
            permission = { kind -> permissionFlow = false; openPermission(kind) },
            loadIcon = appContainer.resolver::icon,
            openRepository = ::openRepository,
        ))
        screen.restoreEditingState(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(appPalette().background)
            addView(screen, FrameLayout.LayoutParams(-1, -1))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.uiState.collect(screen::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        screen.saveEditingState(outState)
        outState.putBoolean("permission_flow", permissionFlow)
        pendingPermission?.let { outState.putString("pending_permission", it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::screen.isInitialized) screen.dispose()
        super.onDestroy()
    }

    private fun requestNextPermission() {
        val state = appContainer.permissionChecker.read(appContainer.bridge.connected.value)
        when {
            !state.accessibility -> openPermission(PermissionKind.ACCESSIBILITY)
            !state.overlay -> openPermission(PermissionKind.OVERLAY)
            else -> { permissionFlow = false; pendingPermission = null; model.refreshPermissions() }
        }
    }

    private fun openRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.repository_url))).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        // Match web handling locally; OEM browsers need not expose CATEGORY_APP_BROWSER.
        try {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val browser = packageManager.resolveActivity(probe,
                PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER)
            if (browser?.activityInfo != null && browser.filter?.countDataAuthorities() == 0) {
                startActivity(Intent(intent).setPackage(browser.activityInfo.packageName))
                return
            }
        } catch (_: ActivityNotFoundException) {
            // Fall through if the preferred browser is no longer available.
        } catch (_: SecurityException) {
            // The system web handler may still be available.
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No available browser: stay on this screen without a message.
        } catch (_: SecurityException) {
            // A system restriction should have the same silent behavior.
        }
    }

    private fun openPermission(kind: PermissionKind) {
        pendingPermission = kind
        try {
            permissionLauncher.launch(appContainer.permissionChecker.intent(kind))
        } catch (_: RuntimeException) {
            permissionFlow = false
            pendingPermission = null
            model.message("无法打开系统设置，请在手机设置中手动授权")
        }
    }
}
