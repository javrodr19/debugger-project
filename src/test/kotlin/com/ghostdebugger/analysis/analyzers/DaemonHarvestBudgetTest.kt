package com.ghostdebugger.analysis.analyzers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DaemonHarvestBudgetTest {

    private val tenFiles = (1..10).map { "file$it.kt" }

    @Test
    fun `select caps the list at maxFiles`() {
        val budget = DaemonHarvestBudget(maxFiles = 3, timeBudgetMs = 1_000)
        assertEquals(listOf("file1.kt", "file2.kt", "file3.kt"), budget.select(tenFiles))
    }

    @Test
    fun `select returns everything when under the cap`() {
        val budget = DaemonHarvestBudget(maxFiles = 50, timeBudgetMs = 1_000)
        assertEquals(tenFiles, budget.select(tenFiles))
    }

    @Test
    fun `maxFiles of zero or less means no cap`() {
        assertEquals(tenFiles, DaemonHarvestBudget(0, 1_000).select(tenFiles))
        assertEquals(tenFiles, DaemonHarvestBudget(-1, 1_000).select(tenFiles))
    }

    @Test
    fun `startDeadline is now plus the time budget`() {
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 500, clock = { 1_000L })
        assertEquals(1_500L, budget.startDeadline())
    }

    @Test
    fun `expired flips once the clock reaches the deadline`() {
        var now = 1_000L
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 500, clock = { now })
        val deadline = budget.startDeadline()

        assertFalse(budget.expired(deadline), "not expired at start")
        now = 1_499L
        assertFalse(budget.expired(deadline), "not expired just before deadline")
        now = 1_500L
        assertTrue(budget.expired(deadline), "expired at the deadline")
        now = 9_999L
        assertTrue(budget.expired(deadline), "still expired past the deadline")
    }

    @Test
    fun `time budget of zero or less never expires`() {
        var now = 1_000L
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 0, clock = { now })
        val deadline = budget.startDeadline()
        now = Long.MAX_VALUE - 1
        assertFalse(budget.expired(deadline))
    }
}
