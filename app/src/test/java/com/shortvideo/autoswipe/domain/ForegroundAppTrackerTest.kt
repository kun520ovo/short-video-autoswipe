package com.shortvideo.autoswipe.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ForegroundAppTrackerTest {
    @Test
    fun windowEventsIdentifyTheAppWhenContentCannotBeRead() {
        val tracker = ForegroundAppTracker()
        tracker.record(7, "target.app")
        assertEquals("target.app", tracker.resolve(7, null))
        assertEquals("target.app", tracker.resolve(7, null))
    }

    @Test
    fun keyboardEventsDoNotReplaceTheFocusedApplication() {
        val tracker = ForegroundAppTracker()
        tracker.resolve(7, "target.app")
        tracker.record(90, "keyboard.app")
        assertEquals("target.app", tracker.resolve(7, null))
    }

    @Test
    fun aDialogOwnedByTheSameAppKeepsTheTaskReady() {
        val tracker = ForegroundAppTracker()
        tracker.resolve(7, "target.app")
        tracker.record(8, "target.app")
        assertEquals(SafetyCheck.Ready, checkForeground("target.app", tracker.resolve(8, null)))
    }

    @Test
    fun anUnknownNewWindowDoesNotReuseThePreviousOwner() {
        val tracker = ForegroundAppTracker()
        tracker.resolve(7, "target.app")
        assertNull(tracker.resolve(8, null))
    }

    @Test
    fun freshWindowMetadataWinsOverAnOldEvent() {
        val tracker = ForegroundAppTracker()
        tracker.record(7, "old.app")
        assertEquals("current.app", tracker.resolve(7, "current.app"))
        assertEquals("current.app", tracker.resolve(7, null))
    }

    @Test
    fun missingContentDoesNotCreateAPagePause() {
        assertEquals(SafetyCheck.Ready, checkForeground("target.app", null))
        assertEquals(SafetyCheck.Ready, checkForeground("target.app", "target.app"))
    }

    @Test
    fun aConfirmedDifferentApplicationStillPausesTheTask() {
        val tracker = ForegroundAppTracker()
        tracker.record(8, "other.app")
        val check = checkForeground("target.app", tracker.resolve(8, null)) as SafetyCheck.Blocked
        assertEquals(BlockKind.FOREGROUND, check.kind)
    }

    @Test
    fun anEventWithoutAFocusedApplicationDoesNotProveThatTheAppReturned() {
        val tracker = ForegroundAppTracker()
        tracker.record(7, "target.app")
        assertNull(tracker.resolve(null, null))
    }
}
