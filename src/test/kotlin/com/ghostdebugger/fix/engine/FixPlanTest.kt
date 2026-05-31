package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixPlanTest {
    @Test fun `toEdits returns one edit per applicable operation`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 1, "A"), ReplaceRange(6, 7, "B")))
        assertEquals(listOf(TextEdit(0, 1, "A"), TextEdit(6, 7, "B")), plan.toEdits("hello world"))
    }

    @Test fun `toEdits returns null if any operation is inapplicable`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 1, "A"), ReplaceRange(99, 100, "B")))
        assertNull(plan.toEdits("hello world"))
    }

    @Test fun `applyTo composes the whole plan onto content`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 5, "hello")))
        assertEquals("hello world", plan.toEdits("XXXXX world")!!.applyTo("XXXXX world"))
    }
}
