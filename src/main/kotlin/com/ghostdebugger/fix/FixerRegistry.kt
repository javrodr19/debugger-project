package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue

object FixerRegistry {
    private val fixers: Map<String, Fixer> = listOf(
        NullSafetyFixer(),
        StateInitFixer(),
        AsyncFlowFixer()
    ).associateBy { it.ruleId }

    /**
     * Returns the [Fixer] registered for [issue.ruleId], or null when no
     * deterministic fixer is available (unknown rule, null ruleId, or the
     * fixer's [canFix] returns false for this specific issue instance).
     */
    fun forIssue(issue: Issue): Fixer? {
        val ruleId = issue.ruleId ?: return null
        val fixer = fixers[ruleId] ?: return null
        return if (fixer.canFix(issue)) fixer else null
    }

    /** Returns all registered fixers. Used by [FixerContractTest]. */
    fun all(): List<Fixer> = fixers.values.toList()
}
