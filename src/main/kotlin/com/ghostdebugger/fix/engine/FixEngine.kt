package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Single entry point for deterministic fixing. Phase 1: derive a [CodeFix] from the registered
 * fixer (via [FixDeriver]), adapt it to a single-op [FixPlan], and apply through [FixPlanApplicator]
 * (Tier-1 PSI-validity gate). The [deriveCodeFix] seam is injectable for tests and is where a later
 * phase's AI planner will additionally contribute plans.
 */
class FixEngine(
    private val project: Project,
    private val deriveCodeFix: (Issue, VirtualFile, String) -> CodeFix? =
        { issue, vf, content -> FixDeriver(project).derive(issue, vf, content) },
    private val applicator: FixPlanApplicator = FixPlanApplicator(),
) {
    /** Derives the deterministic plan for [issue], or null if no fixer applies. */
    fun planFor(issue: Issue, virtualFile: VirtualFile, content: String): FixPlan? =
        deriveCodeFix(issue, virtualFile, content)?.toFixPlan(content)

    /** Applies an already-derived [plan]. The apply seam a later phase also uses. */
    fun apply(plan: FixPlan, virtualFile: VirtualFile): FixApplyResult =
        applicator.apply(plan, virtualFile, project)

    /** Derive + apply. Returns Rejected when no deterministic fixer produces an applicable plan. */
    fun fix(issue: Issue, virtualFile: VirtualFile, content: String): FixApplyResult {
        val plan = planFor(issue, virtualFile, content)
            ?: return FixApplyResult.Rejected("No deterministic fix available for ${issue.ruleId}.")
        return apply(plan, virtualFile)
    }
}
