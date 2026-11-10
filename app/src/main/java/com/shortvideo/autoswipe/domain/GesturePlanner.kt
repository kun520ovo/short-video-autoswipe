package com.shortvideo.autoswipe.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.random.Random

data class PixelPoint(val x: Float, val y: Float)
data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun expanded(padding: Float) = PixelRect(left - padding, top - padding, right + padding, bottom + padding)
    fun contains(point: PixelPoint) = point.x in left..right && point.y in top..bottom
    fun intersects(other: PixelRect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun inside(other: PixelRect) = left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom
}

data class SwipePath(val start: PixelPoint, val end: PixelPoint, val durationMs: Long)

object GesturePlanner {
    fun contentBounds(screen: PixelRect, window: PixelRect, density: Float): PixelRect? {
        val bounds = PixelRect(
            max(screen.left, window.left), max(screen.top, window.top),
            min(screen.right, window.right), min(screen.bottom, window.bottom),
        )
        if (bounds.width <= 0 || bounds.height <= 0 || density <= 0) return null
        val content = PixelRect(
            bounds.left + 8 * density, bounds.top + bounds.height * .10f,
            bounds.right - 8 * density, bounds.bottom - bounds.height * .12f,
        )
        return content.takeIf { it.width > 0 && it.height > 0 }
    }

    private data class Span(val start: Float, val end: Float)
    private data class Route(val x: Float, val startY: Float, val endY: Float) {
        val length get() = startY - endY
    }

    fun swipe(content: PixelRect, blocked: List<PixelRect>, clearance: Float, random: Random = Random.Default): SwipePath? {
        if (clearance <= 0 || content.width < clearance * 4 || content.height < clearance * 8) return null
        val startY = content.top + content.height * .78f
        val endY = content.top + content.height * .32f
        val preferredX = listOf(.50f, .32f, .66f, .20f, .75f, .15f).map { content.left + content.width * it }
        fun clear(route: Route): Boolean {
            val corridor = PixelRect(route.x - clearance, route.endY - clearance, route.x + clearance, route.startY + clearance)
            return corridor.inside(content) && blocked.none { corridor.intersects(it) }
        }
        fun path(route: Route) = SwipePath(PixelPoint(route.x, route.startY), PixelPoint(route.x, route.endY), random.nextLong(1, 101))
        for (x in preferredX) {
            val route = Route(x, startY, endY)
            if (clear(route)) return path(route)
        }

        // Obstacle edges partition the screen into columns with identical blockers.
        // Inspect those gaps rather than declaring six failed samples fully blocked.
        val left = content.left + clearance
        val right = content.right - clearance
        val boundaries = (listOf(left, right) + blocked.flatMap {
            listOf((it.left - clearance).coerceIn(left, right), (it.right + clearance).coerceIn(left, right))
        }).distinct().sorted()
        val candidates = (preferredX + boundaries.zipWithNext { a, b -> (a + b) / 2 }).distinct()
        val minLength = max(clearance * 8, content.height * .22f)
        val preferredLength = startY - endY
        val centerX = content.left + content.width / 2
        val centerY = (startY + endY) / 2
        var best: Route? = null
        var bestDistance = Float.POSITIVE_INFINITY
        for (x in candidates) {
            val occupied = blocked.filter { it.left < x + clearance && it.right > x - clearance }
                .map { Span(it.top - clearance, it.bottom + clearance) }
            for (gap in freeSpans(content.top + clearance, content.bottom - clearance, occupied)) {
                val length = min(preferredLength, gap.end - gap.start)
                if (length < minLength) continue
                val bottom = startY.coerceIn(min(gap.end, gap.start + length), gap.end)
                val route = Route(x, bottom, max(gap.start, bottom - length))
                if (route.length < minLength || !clear(route)) continue
                val distance = abs(x - centerX) + abs((route.startY + route.endY) / 2 - centerY)
                val previous = best
                if (previous == null || route.length > previous.length + .5f ||
                    (abs(route.length - previous.length) <= .5f && distance < bestDistance)) {
                    best = route
                    bestDistance = distance
                }
            }
        }
        return best?.let { path(it) }
    }

    private fun freeSpans(start: Float, end: Float, occupied: List<Span>): List<Span> {
        val result = mutableListOf<Span>()
        var cursor = start
        for (span in occupied.sortedBy { it.start }) {
            val left = max(start, span.start)
            val right = min(end, span.end)
            if (right <= cursor || right <= left) continue
            if (left > cursor) result += Span(cursor, left)
            cursor = max(cursor, right)
            if (cursor >= end) break
        }
        if (cursor < end) result += Span(cursor, end)
        return result
    }

    fun doubleTap(content: PixelRect, blocked: List<PixelRect>, clearance: Float): PixelPoint? =
        listOf(.50f to .48f, .34f to .48f, .62f to .45f, .24f to .55f, .72f to .52f)
            .map { (x, y) -> PixelPoint(content.left + content.width * x, content.top + content.height * y) }
            .firstOrNull { point ->
                val contact = PixelRect(point.x - clearance, point.y - clearance, point.x + clearance, point.y + clearance)
                contact.inside(content) && blocked.none { contact.intersects(it) }
            }

    fun overlayPosition(preferred: PixelPoint?, width: Float, height: Float, usable: PixelRect): PixelPoint =
        clampWindow(preferred ?: PixelPoint(usable.left, usable.top + (usable.height - height) / 2), width, height, usable)

    fun clampWindow(preferred: PixelPoint, width: Float, height: Float, usable: PixelRect) = PixelPoint(
        preferred.x.coerceIn(usable.left, max(usable.left, usable.right - width)),
        preferred.y.coerceIn(usable.top, max(usable.top, usable.bottom - height)),
    )

}
