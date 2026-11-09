package com.shortvideo.autoswipe.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.shortvideo.autoswipe.model.TargetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppResolver(context: Context) {
    private val application = context.applicationContext
    private val packages = application.packageManager

    suspend fun installedApps(): List<TargetApp> = withContext(Dispatchers.IO) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = if (Build.VERSION.SDK_INT >= 33) {
            packages.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(query, 0)
        }
        results.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (!activity.exported || !activity.enabled || activity.applicationInfo?.enabled != true || activity.packageName == application.packageName) {
                return@mapNotNull null
            }
            val label = runCatching { info.loadLabel(packages).toString().trim() }.getOrNull()
                ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            TargetApp(activity.packageName, ComponentName(activity.packageName, activity.name).flattenToString(), label)
        }.distinctBy { it.component }.sortedBy { it.label }
    }

    suspend fun icon(target: TargetApp): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val component = ComponentName.unflattenFromString(target.component) ?: return@runCatching null
            packages.getActivityIcon(component).toBitmap(64, 64)
        }.getOrNull()
    }

    fun launch(context: Context, target: TargetApp): Result<Unit> = runCatching {
        val component = ComponentName.unflattenFromString(target.component) ?: error("应用入口无效")
        check(component.packageName == target.packageName && target.packageName != application.packageName)
        val activity = if (Build.VERSION.SDK_INT >= 33) {
            packages.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getActivityInfo(component, 0)
        }
        check(activity.enabled && activity.exported && activity.applicationInfo?.enabled == true)
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }
}
