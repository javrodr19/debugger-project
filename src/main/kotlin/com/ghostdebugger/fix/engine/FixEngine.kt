package com.ghostdebugger.fix.engine

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.coroutines.CoroutineContext

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

    /**
     * Derive + apply with the Tier-2 re-analysis verify gate. Returns Rejected when no deterministic
     * fixer applies, the candidate is not PSI-valid, or the gate rejects it. [reanalyze] defaults to
     * a single-file static pass over the live (committed) candidate; [baselineForFile] is the set of
     * issues already known for the file (its current findings). Must be called from a coroutine.
     */
    suspend fun fixVerified(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineForFile: List<Issue>,
        reanalyze: suspend () -> List<Issue> = { SingleFileStaticReanalysis(project).issuesFor(virtualFile) },
        edtContext: CoroutineContext = AegisWriteSafeEdt,
    ): FixApplyResult {
        val plan = planFor(issue, virtualFile, content)
            ?: return FixApplyResult.Rejected("No deterministic fix available for ${issue.ruleId}.")
        return applicator.applyVerified(
            plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext,
        )
    }

    /**
     * AI-supervised fix loop. Tries the deterministic plan first; if absent or rejected by the
     * verify gate, asks [aiService] (when non-null) for a [FixPlan] up to [maxAiAttempts] times,
     * feeding each rejection reason back as planner feedback and applying every candidate through
     * the same Tier-2 gate. Returns the first [FixApplyResult.Success] or the last rejection.
     * AI-optional: with [aiService] == null this is exactly the deterministic verified path.
     *
     * Acceptance is fully deterministic (the gate). The AI only proposes and revises.
     */
    suspend fun fixSupervised(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineForFile: List<Issue>,
        aiService: AIService?,
        reanalyze: suspend () -> List<Issue> = { SingleFileStaticReanalysis(project).issuesFor(virtualFile) },
        maxAiAttempts: Int = 2,
        edtContext: CoroutineContext = AegisWriteSafeEdt,
        applyVerified: suspend (FixPlan) -> FixApplyResult = { plan ->
            applicator.applyVerified(plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext)
        },
    ): FixApplyResult {
        var lastReason = "No deterministic fix available for ${issue.ruleId}."

        // 1) Deterministic plan first.
        planFor(issue, virtualFile, content)?.let { plan ->
            when (val r = applyVerified(plan)) {
                is FixApplyResult.Success -> return r
                is FixApplyResult.Rejected -> lastReason = r.reason
                is FixApplyResult.Failed -> lastReason = r.throwable.message ?: lastReason
            }
        }

        // 2) AI-supervised attempts with rejection feedback.
        if (aiService != null) {
            var feedback: String? = null
            repeat(maxAiAttempts) {
                val plan = aiService.proposeFixPlan(issue, content, feedback)
                if (plan != null) {
                    when (val r = applyVerified(plan)) {
                        is FixApplyResult.Success -> return r
                        is FixApplyResult.Rejected -> { feedback = r.reason; lastReason = r.reason }
                        is FixApplyResult.Failed -> {
                            feedback = r.throwable.message ?: "Fix failed."
                            lastReason = feedback ?: lastReason
                        }
                    }
                }
            }
        }
        return FixApplyResult.Rejected(lastReason)
    }
}
