package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.TargetApp
import java.text.Normalizer
import java.util.Locale

data class AppMatch(val candidates: List<TargetApp>, val selected: TargetApp?)

object AppNameMatcher {
    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun match(apps: List<TargetApp>, query: String, selectedComponent: String?): AppMatch {
        val key = normalize(query)
        if (key.isEmpty()) return AppMatch(emptyList(), null)
        val exact = apps.filter { normalize(it.label) == key }
        val candidates = (exact.ifEmpty { apps.filter { normalize(it.label).contains(key) } })
            .distinctBy { it.component }
            .sortedWith(compareBy<TargetApp> { it.label }.thenBy { it.packageName }.thenBy { it.component })
        val selected = candidates.firstOrNull { it.component == selectedComponent } ?: candidates.singleOrNull()
        return AppMatch(candidates, selected)
    }
}
