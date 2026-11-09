package com.shortvideo.autoswipe.model

object TaskDefaults {
    const val APP_NAME = "抖音极速版"
    const val SWIPE_COUNT = 2000
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 10
    const val LIKE_PERCENT = 10
}

data class TaskConfig(
    val appName: String = TaskDefaults.APP_NAME,
    val swipeCount: Int = TaskDefaults.SWIPE_COUNT,
    val minSeconds: Int = TaskDefaults.MIN_SECONDS,
    val maxSeconds: Int = TaskDefaults.MAX_SECONDS,
    val randomLike: Boolean = false,
    val likePercent: Int = TaskDefaults.LIKE_PERCENT,
) {
    fun isValid(): Boolean =
        appName.isNotBlank() && swipeCount in 1..100_000 &&
            minSeconds in 1..3600 && maxSeconds in minSeconds..3600 &&
            (!randomLike || likePercent in 1..100)
}

data class FormDraft(
    val appName: String = TaskDefaults.APP_NAME,
    val count: String = TaskDefaults.SWIPE_COUNT.toString(),
    val minSeconds: String = TaskDefaults.MIN_SECONDS.toString(),
    val maxSeconds: String = TaskDefaults.MAX_SECONDS.toString(),
    val randomLike: Boolean = false,
    val likePercent: String = TaskDefaults.LIKE_PERCENT.toString(),
    val selectedComponent: String? = null,
) {
    /** Fill empty fields only when Start is requested, preserving the editing draft. */
    fun withStartDefaults(): FormDraft = copy(
        appName = appName.ifBlank { TaskDefaults.APP_NAME },
        count = count.ifBlank { TaskDefaults.SWIPE_COUNT.toString() },
        minSeconds = minSeconds.ifBlank { TaskDefaults.MIN_SECONDS.toString() },
        maxSeconds = maxSeconds.ifBlank { TaskDefaults.MAX_SECONDS.toString() },
        likePercent = likePercent.ifBlank { TaskDefaults.LIKE_PERCENT.toString() },
        selectedComponent = if (appName.isBlank()) null else selectedComponent,
    )

    fun errors(): Map<String, String> = buildMap {
        if (appName.isBlank()) put("app", "请填写应用名称。")
        val countValue = count.toIntOrNull()
        if (count.isNotBlank() && (countValue == null || countValue !in 1..100_000)) {
            put("count", "填写 1–100000 之间的整数")
        }
        val minimum = minSeconds.toIntOrNull()
        val maximum = maxSeconds.toIntOrNull()
        if ((minSeconds.isNotBlank() && (minimum == null || minimum !in 1..3600)) ||
            (maxSeconds.isNotBlank() && (maximum == null || maximum !in 1..3600))) {
            put("time", "填写 1–3600 秒之间的整数")
        } else if (minimum != null && maximum != null && minimum > maximum) {
            put("time", "最短时间不能大于最长时间")
        }
        val probability = likePercent.toIntOrNull()
        if (randomLike && (probability == null || probability !in 1..100)) put("like", "点赞概率应为 1–100%")
    }

    fun toConfig(): TaskConfig? {
        if (errors().isNotEmpty()) return null
        return TaskConfig(
            appName.trim(), count.toIntOrNull() ?: return null,
            minSeconds.toIntOrNull() ?: return null, maxSeconds.toIntOrNull() ?: return null,
            randomLike, likePercent.toIntOrNull() ?: TaskDefaults.LIKE_PERCENT,
        )
    }

    companion object {
        fun from(config: TaskConfig, target: TargetApp) = FormDraft(
            config.appName, config.swipeCount.toString(), config.minSeconds.toString(),
            config.maxSeconds.toString(), config.randomLike, config.likePercent.toString(), target.component,
        )
    }
}

data class TargetApp(val packageName: String, val component: String, val label: String)

enum class RunStatus { IDLE, RUNNING, PAUSED, STOPPED, COMPLETED }
enum class RunControlAction { PAUSE, RESUME, STOP }
enum class RunStage { STARTUP, PREPARE_CYCLE, WAIT_BEFORE_LIKE, LIKE, WAIT_AFTER_LIKE, SWIPE, SWIPE_RETRY_WAIT }

data class RunSession(
    val id: String,
    val config: TaskConfig,
    val target: TargetApp,
    val completed: Int = 0,
    val stage: RunStage = RunStage.STARTUP,
    val waitRemainingMs: Long = 10_000,
    val dwellMs: Long = 0,
    val afterLikeMs: Long = 0,
    val retries: Int = 0,
    // Counts completed double-tap gestures, without inspecting the app's like state.
    val likeCount: Int = 0,
    val likeCountUpdatedAtMillis: Long? = null,
)

data class LogEntry(val timeMillis: Long, val message: String) {
    companion object {
        const val MAX_MESSAGE_LENGTH = 240
    }
}

internal fun intervalSeconds(milliseconds: Long): String {
    val value = milliseconds.coerceAtLeast(0)
    val fraction = (value % 1000).toString().padStart(3, '0').trimEnd('0')
    return (value / 1000).toString() + if (fraction.isEmpty()) "" else "." + fraction
}

data class RunState(
    val status: RunStatus = RunStatus.IDLE,
    val session: RunSession? = null,
    val latestLog: LogEntry? = null,
    val pauseReason: String? = null,
    val overlayVisible: Boolean = false,
) {
    val locked: Boolean get() = status == RunStatus.RUNNING || status == RunStatus.PAUSED
    val running: Boolean get() = status == RunStatus.RUNNING
    val hasOverlayContent: Boolean
        get() = overlayVisible && session != null && (locked || status == RunStatus.COMPLETED)
    val compactLabel: String
        get() = label + " · " + (session?.completed ?: 0) + "/" + (session?.config?.swipeCount ?: TaskDefaults.SWIPE_COUNT)
    val label: String
        get() = when (status) {
            RunStatus.IDLE -> "待运行"
            RunStatus.RUNNING -> if (session?.stage == RunStage.STARTUP) "启动中" else "运行中"
            RunStatus.PAUSED -> "已暂停"
            RunStatus.STOPPED -> "已停止"
            RunStatus.COMPLETED -> "已完成"
        }
}
