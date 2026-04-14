package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class NullSafetyFixer : Fixer {
    override val ruleId = "AEG-NULL-001"
    override val description =
        "Replaces direct property access on a known-null variable with optional chaining (?.)."

    private val titleVarRegex = Regex("""Null reference: (\w+)\.""")

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.NULL_SAFETY

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        val varName = titleVarRegex.find(issue.title)?.groupValues?.get(1) ?: return null
        val lines = fileContent.lines()
        val lineIndex = issue.line - 1
        if (lineIndex < 0 || lineIndex >= lines.size) return null
        val original = lines[lineIndex]

        // Replace first occurrence of `varName.x` that is not already optional-chained.
        val safeRegex = Regex("""(?<!\?)(\b${Regex.escape(varName)})\.""")
        if (!safeRegex.containsMatchIn(original)) return null
        val fixed = safeRegex.replaceFirst(original, "$1?.")

        if (fixed == original) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Add optional chaining: $varName. → $varName?.",
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
