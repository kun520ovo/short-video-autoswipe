package com.shortvideo.autoswipe.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortvideo.autoswipe.AppContainer
import com.shortvideo.autoswipe.domain.AppMatch
import com.shortvideo.autoswipe.domain.AppNameMatcher
import com.shortvideo.autoswipe.domain.CommandResult
import com.shortvideo.autoswipe.model.*
import com.shortvideo.autoswipe.platform.PermissionState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class AppListState(val values: List<TargetApp> = emptyList(), val loading: Boolean = true, val error: String? = null)
private data class UiFlags(
    val touched: Set<String> = emptySet(),
    val submitted: Boolean = false,
    val picker: Boolean = false,
    val message: String? = null,
    val startNeedsApp: Boolean = false,
)
private data class FormBundle(val draft: FormDraft, val apps: AppListState, val flags: UiFlags, val permissions: PermissionState)

data class MainUiState(
    val draft: FormDraft = FormDraft(),
    val run: RunState = RunState(),
    val permissions: PermissionState = PermissionState(false, false, false),
    val match: AppMatch = AppMatch(emptyList(), null),
    val errors: Map<String, String> = emptyMap(),
    val ready: Boolean = false,
    val appLoading: Boolean = true,
    val picker: Boolean = false,
    val message: String? = null,
    val appLoadError: String? = null,
) {
    val primaryLabel get() = when {
        !ready -> "正在读取设置"
        !permissions.granted -> "去完成授权"
        !permissions.connected -> "服务连接中"
        else -> "开始挂机"
    }
}

class MainViewModel(val container: AppContainer) : ViewModel() {
    private val draft = MutableStateFlow(FormDraft())
    private val flags = MutableStateFlow(UiFlags())
    private val apps = MutableStateFlow(AppListState())
    private val permissions = MutableStateFlow(container.permissionChecker.read(container.bridge.connected.value))
    private val loaded = MutableStateFlow(false)
    private var saveJob: Job? = null
    private var appsJob: Job? = null

    private val form = combine(draft, apps, flags, permissions) { value, list, ui, access -> FormBundle(value, list, ui, access) }
    val uiState = combine(form, container.engine.state, loaded) { form, run, draftReady ->
        val session = run.session?.takeIf { run.locked }
        val effective = if (session != null) FormDraft.from(session.config, session.target) else form.draft
        val match = if (session != null) {
            AppMatch(listOf(session.target), session.target)
        } else if (form.apps.loading || form.apps.error != null) {
            AppMatch(emptyList(), null)
        } else AppNameMatcher.match(form.apps.values, effective.appName, effective.selectedComponent)
        val allErrors = effective.errors().toMutableMap()
        if (!form.apps.loading && form.apps.error == null && effective.appName.isNotBlank() && match.candidates.isEmpty()) {
            allErrors["app"] = "未找到该应用，请检查名称"
        }
        val visibleErrors = allErrors.filterKeys { key ->
            form.flags.submitted || key in form.flags.touched ||
                (key == "app" && (effective.appName.isBlank() || !form.apps.loading))
        }
        // Derive Start feedback from the current lookup so it clears after recovery.
        val appStartMessage = if (form.flags.startNeedsApp && session == null) {
            when {
                form.apps.loading -> "正在查找应用，请稍后再开始"
                form.apps.error != null || match.selected == null -> "请先匹配目标应用"
                else -> null
            }
        } else null
        MainUiState(
            effective, run, form.permissions, match, visibleErrors,
            draftReady, form.apps.loading, form.flags.picker,
            form.flags.message ?: appStartMessage ?: run.pauseReason,
            appLoadError = form.apps.error.takeIf { session == null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            try { draft.value = container.settings.readDraft() }
            catch (_: IOException) { message("设置暂时无法读取，已使用默认值") }
            finally { loaded.value = true }
            var wasLocked = false
            container.engine.state.collect { state ->
                val session = state.session
                if (session != null && (state.locked || wasLocked)) {
                    draft.value = FormDraft.from(session.config, session.target)
                    if (!state.locked) saveLater()
                }
                wasLocked = state.locked
            }
        }
        viewModelScope.launch { container.bridge.connected.collect { refreshPermissions() } }
        viewModelScope.launch {
            var previous: String? = null
            container.bridge.error.collect { error ->
                if (error != null || flags.value.message == previous) message(error)
                previous = error
            }
        }
    }

    fun refresh() {
        refreshPermissions()
        container.bridge.refresh()
        appsJob?.cancel()
        apps.value = apps.value.copy(loading = true, error = null)
        appsJob = viewModelScope.launch {
            try {
                apps.value = AppListState(container.resolver.installedApps(), false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                apps.value = AppListState(loading = false, error = "无法读取已安装应用，请稍后重试。")
            }
        }
    }

    fun refreshPermissions() {
        permissions.value = container.permissionChecker.read(container.bridge.connected.value)
    }

    fun edit(field: String, value: String) {
        if (container.engine.state.value.locked || !loaded.value) return
        draft.value = when (field) {
            "app" -> draft.value.copy(appName = value.take(60), selectedComponent = null)
            "count" -> draft.value.copy(count = value.take(8))
            "min" -> draft.value.copy(minSeconds = value.take(6))
            "max" -> draft.value.copy(maxSeconds = value.take(6))
            "like" -> draft.value.copy(likePercent = value.take(4))
            else -> return
        }
        flags.value = flags.value.copy(message = null, startNeedsApp = false)
        saveLater()
    }

    fun setLike(enabled: Boolean) {
        if (container.engine.state.value.locked) return
        draft.value = draft.value.copy(randomLike = enabled)
        saveLater()
    }

    fun touched(field: String) { flags.value = flags.value.copy(touched = flags.value.touched + field) }
    fun openPicker() { if (!container.engine.state.value.locked) flags.value = flags.value.copy(picker = true) }
    fun closePicker() { flags.value = flags.value.copy(picker = false) }
    fun selectApp(app: TargetApp) {
        if (container.engine.state.value.locked) return
        draft.value = draft.value.copy(appName = app.label, selectedComponent = app.component)
        flags.value = flags.value.copy(picker = false, message = null, startNeedsApp = false)
        saveLater()
    }

    fun start(activity: Activity) {
        if (!loaded.value || container.engine.state.value.locked) return
        val value = draft.value.withStartDefaults()
        if (value != draft.value) {
            draft.value = value
            saveLater()
        }
        flags.value = flags.value.copy(submitted = true, message = null, startNeedsApp = false)
        val config = value.toConfig() ?: return
        val list = apps.value
        if (list.loading || list.error != null) {
            flags.value = flags.value.copy(startNeedsApp = true)
            return
        }
        val match = AppNameMatcher.match(list.values, value.appName, value.selectedComponent)
        val target = match.selected
        if (target == null) {
            if (match.candidates.size > 1) openPicker()
            else flags.value = flags.value.copy(startNeedsApp = true)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        viewModelScope.launch {
            val result = container.engine.start(config, target) { container.resolver.launch(activity, target) }
            if (result is CommandResult.Rejected) message(result.message)
            else {
                draft.value = FormDraft.from(config.copy(appName = target.label), target)
                flags.value = UiFlags()
                saveJob?.cancel()
                val saved = draft.value
                container.scope.launch { saveDraft(saved) }
            }
        }
    }

    fun pause() { viewModelScope.launch { container.engine.pause() } }

    fun stop() { viewModelScope.launch { container.engine.stop(); message(null) } }
    fun message(value: String?) { flags.value = flags.value.copy(message = value) }

    private fun saveLater() {
        saveJob?.cancel()
        val value = draft.value
        saveJob = viewModelScope.launch {
            delay(250)
            saveDraft(value)
        }
    }

    private suspend fun saveDraft(value: FormDraft) {
        try { container.settings.saveDraft(value) }
        catch (_: IOException) { message("设置暂时无法保存") }
    }
}
