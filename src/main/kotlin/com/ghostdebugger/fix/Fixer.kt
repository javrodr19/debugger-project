package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue

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
}
