package com.shortvideo.autoswipe

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.shortvideo.autoswipe.apps.AppResolver
import com.shortvideo.autoswipe.data.SettingsRepository
import com.shortvideo.autoswipe.domain.AutomationEngine
import com.shortvideo.autoswipe.domain.MonotonicClock
import com.shortvideo.autoswipe.platform.PermissionChecker
import com.shortvideo.autoswipe.platform.ServiceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AutoSwipeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        container
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AutoSwipeApplication).container

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(context)
    val resolver = AppResolver(context)
    val permissionChecker = PermissionChecker(context)
    val bridge = ServiceBridge()
    val engine = AutomationEngine(
        port = bridge,
        scope = scope,
        dispatcher = Dispatchers.Main.immediate,
        clock = MonotonicClock { SystemClock.elapsedRealtime() },
    )

    init {
        scope.launch { bridge.changes.collect { engine.environmentChanged() } }
    }
}
