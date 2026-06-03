package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.AddPromiseCatch
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixOperation
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.fix.engine.SurroundWithTryCatch
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class AsyncFlowFixer : Fixer {
    override val ruleId = "AEG-ASYNC-001"
    override val description =
        "Appends .catch(console.error) to a Promise chain that is missing an error handler."

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId &&
            issue.type in setOf(IssueType.UNHANDLED_PROMISE, IssueType.MISSING_ERROR_HANDLING)

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        if (!canFix(issue)) return null
        val op: FixOperation? = when (issue.type) {
            IssueType.UNHANDLED_PROMISE -> AddPromiseCatch(issue.line)
            IssueType.MISSING_ERROR_HANDLING -> SurroundWithTryCatch(issue.line, issue.line)
            else -> null
        }
        // Confirm the op actually applies to the current content before proposing it (no-false-positive).
        val edit = op?.toEdit(ctx) ?: return null
        @Suppress("UNUSED_VARIABLE") val ignored = edit
        return FixPlan(issue.id, listOf(op))
    }

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        if (!canFix(issue)) return null
        val lines = fileContent.lines()
        val lineIndex = issue.line - 1
        if (lineIndex < 0 || lineIndex >= lines.size) return null
        val original = lines[lineIndex]

        // Pattern: line contains .then(...) and ends with ); after trimming.
        if (!original.contains(".then(") || !original.trimEnd().endsWith(");")) return null
        val fixed = original.trimEnd().dropLast(1) + ".catch(console.error);"

        if (fixed == original.trimEnd()) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Add .catch(console.error) to unhandled Promise chain.",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 1.0
        )
    }
}
