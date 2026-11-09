package com.shortvideo.autoswipe.domain

import com.shortvideo.autoswipe.model.FormDraft
import com.shortvideo.autoswipe.model.TaskConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultTimingTest {
    @Test
    fun newTasksAndFormsUseFiveToTenSeconds() {
        assertEquals(5, TaskConfig().minSeconds)
        assertEquals(10, TaskConfig().maxSeconds)
        assertEquals(TaskConfig(), FormDraft().toConfig())
    }
}
