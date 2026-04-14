package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class AsyncFlowFixer : Fixer {
    override val ruleId = "AEG-ASYNC-001"
    override val description =
        "Appends .catch(console.error) to a Promise chain that is missing an error handler."

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.UNHANDLED_PROMISE

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
