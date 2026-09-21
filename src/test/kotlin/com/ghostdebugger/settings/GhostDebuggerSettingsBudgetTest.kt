package com.ghostdebugger.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `GhostDebuggerSettings` has a no-arg constructor and `loadState` runs `validate()`, so the
 * clamping rules are reachable without an IDE fixture.
 */
class GhostDebuggerSettingsBudgetTest {

    @Test
    fun `daemon budgets have sane defaults`() {
        val state = GhostDebuggerSettings.State()
        assertEquals(150, state.daemonFileBudget)
        assertEquals(60_000L, state.daemonTimeBudgetMs)
    }

    @Test
    fun `non-positive daemon file budget is clamped to the default`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = 0))
        assertEquals(150, settings.state.daemonFileBudget)

        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = -5))
        assertEquals(150, settings.state.daemonFileBudget)
    }

    @Test
    fun `non-positive daemon time budget is clamped to the default`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonTimeBudgetMs = 0))
        assertEquals(60_000L, settings.state.daemonTimeBudgetMs)
    }

    @Test
    fun `valid daemon budgets are preserved`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = 42, daemonTimeBudgetMs = 5_000))
        assertEquals(42, settings.state.daemonFileBudget)
        assertEquals(5_000L, settings.state.daemonTimeBudgetMs)
    }
}
