package com.shortvideo.autoswipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shortvideo.autoswipe.platform.PermissionKind
import com.shortvideo.autoswipe.ui.AutoSwipeTheme
import com.shortvideo.autoswipe.ui.MainViewModel
import com.shortvideo.autoswipe.ui.TaskScreen

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
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
        setContent {
            AutoSwipeTheme {
                TaskScreen(
                    model = model,
                    onStart = { model.start(this) },
                    onPause = { resume -> model.pauseOrResume(resume) },
                    onAllPermissions = { permissionFlow = true; requestNextPermission() },
                    onPermission = { kind -> permissionFlow = false; openPermission(kind) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_flow", permissionFlow)
        pendingPermission?.let { outState.putString("pending_permission", it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun requestNextPermission() {
        val state = appContainer.permissionChecker.read(appContainer.bridge.connected.value)
        when {
            !state.accessibility -> openPermission(PermissionKind.ACCESSIBILITY)
            !state.overlay -> openPermission(PermissionKind.OVERLAY)
            else -> { permissionFlow = false; pendingPermission = null; model.refreshPermissions() }
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
