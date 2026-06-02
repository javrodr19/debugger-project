package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.psi.PsiFile

interface Fixer {
    /** Must equal the `ruleId` of the corresponding `Analyzer`. */
    val ruleId: String

    /** One-sentence description of the transformation this fixer applies. */
    val description: String

    /**
     * Returns true if this fixer can produce a deterministic fix for [issue].
     * The default implementation checks [issue.ruleId]; override to further
     * restrict by issue type when one analyzer emits multiple issue types.
     */
    fun canFix(issue: Issue): Boolean = issue.ruleId == ruleId

    /**
     * Generates a deterministic [CodeFix] for [issue] using [fileContent] as the
     * full source text of the file at [issue.filePath].
     * Returns null if the fix cannot be safely derived (pattern not found,
     * line out of range, etc.). A null return causes the caller to fall back to AI.
     */
    fun generateFix(issue: Issue, fileContent: String): CodeFix?

    /**
     * Optional PSI-driven fix path. [FixDeriver] tries this first; on null it
     * falls back to [generateFix].
     *
     * The parameter is `PsiFile` (not `KtFile`) to keep this interface
     * language-neutral; Kotlin fixers cast internally.
     */
    fun generateFixFromPsi(issue: Issue, file: PsiFile): CodeFix? = null

    /**
     * Optional op-emitting path: return a [FixPlan] of semantic [com.ghostdebugger.fix.engine.FixOperation]s.
     * [FixDeriver.derivePlan] tries this BEFORE the [generateFixFromPsi]/[generateFix] CodeFix path.
     * Called inside a read action; [ctx] exposes the file content and (lazily) its PSI. Return null to
     * decline (no safe op) and fall back. Default: unsupported.
     */
    fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? = null
}
