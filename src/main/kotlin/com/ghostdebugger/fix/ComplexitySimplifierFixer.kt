package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.BooleanReturnCollapse
import com.ghostdebugger.fix.engine.CollapseBooleanReturn
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue

/**
 * Deterministic simplifier for high-complexity files (`AEG-CPX-001`). The issue is file-level
 * (line = 1), so this scans the whole file for collapsible boolean-return if/else sites
 * ([BooleanReturnCollapse.sites]) and emits one [CollapseBooleanReturn] per site. Each op resolves
 * its edit against the original content (absolute offsets), so the multi-op plan applies cleanly
 * (the applicator sorts edits descending by offset — no line-shift interference).
 *
 * Declines (null) when no collapsible site exists, leaving the file to the AI extract-method path
 * (a deliberate follow-on). The strict-complexity-decrease verdict is enforced downstream by
 * [com.ghostdebugger.fix.engine.ComplexityVerifier]; this fixer only proposes.
 */
class ComplexitySimplifierFixer : Fixer {
    override val ruleId = "AEG-CPX-001"
    override val description = "Simplifies high-complexity code by collapsing boolean-return if/else into a single return."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val lines = BooleanReturnCollapse.sites(ctx)
        if (lines.isEmpty()) return null
        return FixPlan(issue.id, lines.map { CollapseBooleanReturn(it) })
    }
}
