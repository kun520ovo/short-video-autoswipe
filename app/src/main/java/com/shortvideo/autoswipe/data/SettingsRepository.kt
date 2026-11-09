package com.shortvideo.autoswipe.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.shortvideo.autoswipe.model.FormDraft
import com.shortvideo.autoswipe.model.TaskDefaults
import kotlinx.coroutines.flow.first

private val Context.automationPreferences by preferencesDataStore(
    name = "autoswipe",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.automationPreferences

    private object Keys {
        val app = stringPreferencesKey("app_name")
        val count = stringPreferencesKey("count")
        val minimum = stringPreferencesKey("minimum")
        val maximum = stringPreferencesKey("maximum")
        val like = booleanPreferencesKey("random_like")
        val probability = stringPreferencesKey("like_probability")
        val component = stringPreferencesKey("selected_component")
    }

    suspend fun readDraft(): FormDraft {
        val p = store.data.first()
        return FormDraft(
            p[Keys.app] ?: TaskDefaults.APP_NAME, p[Keys.count] ?: TaskDefaults.SWIPE_COUNT.toString(),
            p[Keys.minimum] ?: TaskDefaults.MIN_SECONDS.toString(),
            p[Keys.maximum] ?: TaskDefaults.MAX_SECONDS.toString(),
            p[Keys.like] ?: false, p[Keys.probability] ?: TaskDefaults.LIKE_PERCENT.toString(), p[Keys.component],
        )
    }

    suspend fun saveDraft(value: FormDraft) {
        store.edit { p ->
            p[Keys.app] = value.appName
            p[Keys.count] = value.count
            p[Keys.minimum] = value.minSeconds
            p[Keys.maximum] = value.maxSeconds
            p[Keys.like] = value.randomLike
            p[Keys.probability] = value.likePercent
            if (value.selectedComponent == null) p.remove(Keys.component) else p[Keys.component] = value.selectedComponent
        }
    }
}
