package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.FormDraft
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.model.TaskDefaults
import com.shortvideo.autoswipe.model.intervalSeconds
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InteractionRulesTest {
    @Test
    fun exactLabelWinsOverSubstringMatch() {
        val apps = listOf(TargetApp("a", "a/.Main", "抖音"), TargetApp("b", "b/.Main", "抖音极速版"))
        assertEquals("a", AppNameMatcher.match(apps, " 抖音 ", null).selected?.packageName)
        assertEquals(2, AppNameMatcher.match(apps, "抖", null).candidates.size)
        assertNull(AppNameMatcher.match(apps, "未安装", null).selected)
    }

    @Test
    fun duplicateLabelsRequireComponentChoice() {
        val a = TargetApp("a", "a/.First", "视频播放器")
        val b = TargetApp("b", "b/.Other", "视频播放器")
        assertNull(AppNameMatcher.match(listOf(a, b), "视频播放器", null).selected)
        assertEquals(b, AppNameMatcher.match(listOf(a, b), "视频播放器", b.component).selected)
    }

    @Test
    fun invalidValuesAreReportedAtTheirFields() {
        assertTrue(FormDraft(count = "0").errors().containsKey("count"))
        assertTrue(FormDraft(minSeconds = "", maxSeconds = "bad").errors().containsKey("time"))
        assertTrue(FormDraft(minSeconds = "3601", maxSeconds = "").errors().containsKey("time"))
        assertTrue(FormDraft(minSeconds = "21", maxSeconds = "20").errors().containsKey("time"))
        assertTrue(FormDraft(randomLike = true, likePercent = "101").errors().containsKey("like"))
        assertTrue(FormDraft(minSeconds = "10", maxSeconds = "10").errors().isEmpty())
    }

    @Test
    fun clearedFieldsStayEmptyAndUnmatchedUntilStartIsRequested() {
        val defaultApp = TargetApp("lite", "lite/.Main", TaskDefaults.APP_NAME)
        val otherApp = TargetApp("full", "full/.Main", "抖音")
        val apps = listOf(otherApp, defaultApp)
        for (blank in listOf("", "   ", "\t\n")) {
            val draft = FormDraft(
                appName = blank, count = blank, minSeconds = blank, maxSeconds = blank,
                selectedComponent = otherApp.component,
            )
            assertEquals(mapOf("app" to "请填写应用名称。"), draft.errors())
            assertNull(draft.toConfig())
            assertTrue(AppNameMatcher.match(apps, draft.appName, draft.selectedComponent).candidates.isEmpty())
            val started = draft.withStartDefaults()
            assertEquals(FormDraft(), started)
            assertTrue(started.errors().isEmpty())
            assertEquals(FormDraft().toConfig(), started.toConfig())
            assertEquals(defaultApp, AppNameMatcher.match(apps, started.appName, started.selectedComponent).selected)
            assertNull(AppNameMatcher.match(listOf(otherApp), started.appName, started.selectedComponent).selected)
            assertEquals(blank, draft.appName)
            assertEquals(blank, draft.count)
            assertEquals(blank, draft.minSeconds)
            assertEquals(blank, draft.maxSeconds)
        }
    }

    @Test
    fun aSuppliedAppNameIsTrimmedWithoutFallingBackToTheDefault() {
        val draft = FormDraft(appName = "  哔哩哔哩  ")
        assertEquals(draft, draft.withStartDefaults())
        assertEquals("哔哩哔哩", requireNotNull(draft.toConfig()).appName)
    }

    @Test
    fun blankLikeProbabilityIsFilledOnlyWhenStartIsRequested() {
        for (enabled in listOf(false, true)) {
            for (blank in listOf("", "   ", "\t\n")) {
                val draft = FormDraft(randomLike = enabled, likePercent = blank)
                if (enabled) {
                    assertTrue(draft.errors().containsKey("like"))
                    assertNull(draft.toConfig())
                }
                val started = draft.withStartDefaults()
                assertEquals(draft.copy(likePercent = "10"), started)
                assertTrue(started.errors().isEmpty())
                assertEquals(10, requireNotNull(started.toConfig()).likePercent)
                assertEquals(blank, draft.likePercent)
            }
        }
    }

    @Test
    fun customAndInvalidNonEmptyLikeProbabilitiesAreNotOverwritten() {
        for (value in listOf("1", "37", "100")) {
            val draft = FormDraft(randomLike = true, likePercent = value)
            assertEquals(draft, draft.withStartDefaults())
            assertEquals(value.toInt(), requireNotNull(draft.withStartDefaults().toConfig()).likePercent)
        }
        for (value in listOf("0", "101", "bad")) {
            val draft = FormDraft(randomLike = true, likePercent = value)
            val started = draft.withStartDefaults()
            assertEquals(draft, started)
            assertTrue(started.errors().containsKey("like"))
            assertNull(started.toConfig())
        }
    }

    @Test
    fun startingFillsOnlyClearedValuesAndKeepsTheSelectedApplication() {
        val draft = FormDraft(
            appName = "其他视频", count = "", minSeconds = "7", maxSeconds = "12",
            randomLike = true, likePercent = "30", selectedComponent = "other/.Main",
        )
        assertEquals(draft.copy(count = "2000"), draft.withStartDefaults())
        val missingMinimum = draft.copy(count = "500", minSeconds = "")
        assertEquals(missingMinimum.copy(minSeconds = "5"), missingMinimum.withStartDefaults())
        val missingMaximum = draft.copy(count = "500", minSeconds = "3", maxSeconds = "")
        assertEquals(missingMaximum.copy(maxSeconds = "10"), missingMaximum.withStartDefaults())
    }

    @Test
    fun invalidNonEmptyValuesAreNotReplacedByDefaults() {
        val invalid = FormDraft(count = "bad", minSeconds = "0", maxSeconds = "3601")
        val started = invalid.withStartDefaults()
        assertEquals(invalid, started)
        assertEquals(setOf("count", "time"), started.errors().keys)
        assertNull(started.toConfig())
    }

    @Test
    fun fillingOneTimeEndpointStillChecksTheResultingRange() {
        val missingMinimum = FormDraft(minSeconds = "", maxSeconds = "3")
        val missingMaximum = FormDraft(minSeconds = "12", maxSeconds = "")
        for (draft in listOf(missingMinimum, missingMaximum)) {
            assertTrue(draft.errors().isEmpty())
            val started = draft.withStartDefaults()
            assertEquals(setOf("time"), started.errors().keys)
            assertNull(started.toConfig())
        }
        assertEquals(missingMinimum.copy(minSeconds = "5"), missingMinimum.withStartDefaults())
        assertEquals(missingMaximum.copy(maxSeconds = "10"), missingMaximum.withStartDefaults())
    }

    @Test
    fun gesturesAvoidOverlayAndUnusableScreen() {
        val content = PixelRect(0f, 0f, 360f, 800f)
        val obstacle = PixelRect(100f, 200f, 270f, 700f)
        val swipe = requireNotNull(GesturePlanner.swipe(content, listOf(obstacle), 8f))
        assertFalse(obstacle.contains(swipe.start))
        assertFalse(obstacle.contains(swipe.end))
        val tap = requireNotNull(GesturePlanner.doubleTap(content, listOf(obstacle), 8f))
        assertFalse(obstacle.contains(tap))
        assertNull(GesturePlanner.swipe(content, listOf(content), 8f))
        assertNull(GesturePlanner.doubleTap(content, listOf(content), 8f))
    }

    @Test
    fun aClearColumnBetweenTheOriginalSamplesCanBeUsed() {
        val content = PixelRect(0f, 0f, 1000f, 2000f)
        val blocked = listOf(PixelRect(0f, 0f, 400f, 2000f), PixelRect(450f, 0f, 1000f, 2000f))
        val swipe = requireNotNull(GesturePlanner.swipe(content, blocked, 8f))
        assertTrue(swipe.start.x in 408f..442f)
        assertEquals(swipe.start.x, swipe.end.x)
        assertEquals(1560f, swipe.start.y)
        assertEquals(640f, swipe.end.y)
    }

    @Test
    fun aWideMiddleBarDoesNotHideTheFreeSpaceAboveOrBelowIt() {
        val content = PixelRect(0f, 0f, 1000f, 2000f)
        val bar = PixelRect(0f, 850f, 1000f, 1050f)
        val swipe = requireNotNull(GesturePlanner.swipe(content, listOf(bar), 8f))
        val corridor = PixelRect(swipe.start.x - 8, swipe.end.y - 8, swipe.start.x + 8, swipe.start.y + 8)
        assertTrue(corridor.inside(content))
        assertFalse(corridor.intersects(bar))
        assertTrue(swipe.start.y - swipe.end.y >= content.height * .22f)
    }

    @Test
    fun tinyGapsAreNotMistakenForAUsableSwipeRoute() {
        val content = PixelRect(0f, 0f, 1000f, 2000f)
        val bars = (0..6).map { y -> PixelRect(0f, y * 300f, 1000f, y * 300f + 200f) }
        assertNull(GesturePlanner.swipe(content, bars, 8f))
    }

    @Test
    fun gapSearchRespectsContentOffsets() {
        val content = PixelRect(50f, 100f, 1050f, 2100f)
        val blocked = listOf(PixelRect(50f, 100f, 480f, 2100f), PixelRect(520f, 100f, 1050f, 2100f))
        val swipe = requireNotNull(GesturePlanner.swipe(content, blocked, 8f))
        assertTrue(swipe.start.x in 488f..512f)
        assertEquals(1660f, swipe.start.y)
        assertEquals(740f, swipe.end.y)
    }

    @Test
    fun expansionPreservesPreferredCompactPosition() {
        val usable = PixelRect(6f, 24f, 354f, 776f)
        val preferred = PixelPoint(260f, 720f)
        assertEquals(PixelPoint(164f, 616f), GesturePlanner.clampWindow(preferred, 190f, 160f, usable))
        assertEquals(preferred, GesturePlanner.clampWindow(preferred, 94f, 44f, usable))
    }

    @Test
    fun defaultOverlayPlacementIsLeftCenteredAtEitherSize() {
        val usable = PixelRect(6f, 24f, 354f, 776f)
        val expanded = GesturePlanner.overlayPosition(null, 190f, 170f, usable)
        val compact = GesturePlanner.overlayPosition(null, 94f, 44f, usable)
        assertEquals(PixelPoint(6f, 315f), expanded)
        assertEquals(PixelPoint(6f, 378f), compact)
        assertEquals(expanded.y + 85f, compact.y + 22f)
    }

    @Test
    fun landscapeScreensHaveUsableSwipeAndTapCoordinates() {
        val screen = PixelRect(0f, 0f, 800f, 360f)
        val swipe = requireNotNull(GesturePlanner.swipe(screen, emptyList(), 8f))
        val tap = requireNotNull(GesturePlanner.doubleTap(screen, emptyList(), 8f))
        assertTrue(screen.contains(swipe.start))
        assertTrue(screen.contains(swipe.end))
        assertTrue(swipe.start.y > swipe.end.y)
        assertTrue(screen.contains(tap))
    }
    @Test
    fun intervalDisplayPreservesMillisecondsWithoutExtraZeros() {
        assertEquals("5", intervalSeconds(5_000))
        assertEquals("5.001", intervalSeconds(5_001))
        assertEquals("5.05", intervalSeconds(5_050))
        assertEquals("10", intervalSeconds(10_000))
    }

    @Test
    fun swipeDurationsVaryWithinOneToOneHundredMillisecondsWithoutChangingTheRoute() {
        val window = PixelRect(0f, 0f, 360f, 800f)
        val random = Random(42)
        val swipes = List(5_000) { requireNotNull(GesturePlanner.swipe(window, emptyList(), 8f, random)) }
        assertTrue(swipes.all { it.durationMs in 1L..100L })
        assertTrue(swipes.any { it.durationMs == 1L })
        assertTrue(swipes.any { it.durationMs == 100L })
        assertTrue(swipes.all { it.start == PixelPoint(180f, 624f) && it.end == PixelPoint(180f, 256f) })
    }
}
