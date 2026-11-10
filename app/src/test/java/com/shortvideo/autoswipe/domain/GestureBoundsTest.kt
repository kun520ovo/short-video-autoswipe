package com.shortvideo.autoswipe.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GestureBoundsTest {
    private val screen = PixelRect(0f, 24f, 1080f, 2352f)

    @Test
    fun splitScreenGesturesStayInsideTheFocusedApplication() {
        val windows = listOf(
            PixelRect(0f, 24f, 1080f, 1188f),
            PixelRect(0f, 1188f, 1080f, 2352f),
            PixelRect(0f, 24f, 540f, 2352f),
            PixelRect(540f, 24f, 1080f, 2352f),
            PixelRect(250f, 500f, 950f, 1800f),
        )
        for (window in windows) assertGesturesInsideWindow(screen, window)
    }

    @Test
    fun offScreenWindowEdgesAreClippedToTheUsableDisplay() {
        assertGesturesInsideWindow(screen, PixelRect(-200f, -100f, 600f, 1500f))
        assertGesturesInsideWindow(screen, PixelRect(500f, 1000f, 1400f, 2700f))
    }

    @Test
    fun landscapeWindowsAndDisplayOffsetsArePreserved() {
        val landscape = PixelRect(1920f, 24f, 3840f, 1080f)
        assertGesturesInsideWindow(landscape, PixelRect(2880f, 24f, 3840f, 1080f))
    }

    @Test
    fun emptyOrInvisibleWindowsDoNotProduceGestureCoordinates() {
        for (window in listOf(
            PixelRect(0f, 0f, 0f, 0f),
            PixelRect(1080f, 24f, 1500f, 2352f),
            PixelRect(0f, 2352f, 1080f, 2700f),
            PixelRect(600f, 1000f, 500f, 1200f),
            PixelRect(0f, 24f, 40f, 500f),
        )) {
            assertNull(GesturePlanner.contentBounds(screen, window, 3f), window.toString())
        }
    }

    @Test
    fun fullScreenGesturesKeepTheExistingPositions() {
        val display = PixelRect(0f, 0f, 360f, 800f)
        val content = requireNotNull(GesturePlanner.contentBounds(display, display, 1f))
        assertEquals(PixelRect(8f, 80f, 352f, 704f), content)
    }

    private fun assertGesturesInsideWindow(display: PixelRect, window: PixelRect) {
        val content = requireNotNull(GesturePlanner.contentBounds(display, window, 3f))
        assertTrue(content.inside(window), "Content escaped the application window: $window")
        assertTrue(content.inside(display), "Content escaped the usable display: $display")
        val swipe = requireNotNull(GesturePlanner.swipe(content, emptyList(), 24f))
        val tap = requireNotNull(GesturePlanner.doubleTap(content, emptyList(), 24f))
        for (point in listOf(swipe.start, swipe.end, tap)) {
            val contact = PixelRect(point.x - 24f, point.y - 24f, point.x + 24f, point.y + 24f)
            assertTrue(contact.inside(window), "Gesture escaped the application window: $window")
            assertTrue(contact.inside(display), "Gesture escaped the usable display: $display")
        }
    }
}
