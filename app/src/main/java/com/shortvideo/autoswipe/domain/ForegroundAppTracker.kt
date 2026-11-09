package com.shortvideo.autoswipe.domain

/** Keep only the latest event and focused window; never retain page nodes or text. */
class ForegroundAppTracker {
    private data class WindowApp(val id: Int, val packageName: String)
    private var eventWindow: WindowApp? = null
    private var focusedWindow: WindowApp? = null

    fun record(windowId: Int, packageName: String?) {
        val name = packageName?.takeIf { it.isNotBlank() } ?: return
        if (windowId >= 0) eventWindow = WindowApp(windowId, name)
    }

    fun resolve(windowId: Int?, rootPackage: String?): String? {
        if (windowId == null || windowId < 0) return null
        val event = eventWindow
        eventWindow = null
        val name = rootPackage?.takeIf { it.isNotBlank() }
            ?: event?.takeIf { it.id == windowId }?.packageName
            ?: focusedWindow?.takeIf { it.id == windowId }?.packageName
        focusedWindow = name?.let { WindowApp(windowId, it) }
        return name
    }
}

/** Unreadable page content is not evidence that the user switched applications. */
internal fun checkForeground(targetPackage: String, visiblePackage: String?): SafetyCheck =
    if (visiblePackage != null && visiblePackage != targetPackage) {
        SafetyCheck.Blocked(BlockKind.FOREGROUND, "目标应用已离开前台")
    } else {
        SafetyCheck.Ready
    }
