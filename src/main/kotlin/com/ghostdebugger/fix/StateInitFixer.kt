package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class StateInitFixer : Fixer {
    override val ruleId = "AEG-STATE-001"
    override val description =
        "Changes useState() to useState([]) for state variables used with array methods."

    private val titleVarRegex = Regex("""Uninitialized state used: (\w+)""")

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.STATE_BEFORE_INIT

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        val varName = titleVarRegex.find(issue.title)?.groupValues?.get(1) ?: return null
        val lines = fileContent.lines()

        // Find the useState() declaration for this variable.
        val declRegex = Regex("""const\s+\[\s*${Regex.escape(varName)}\s*,""")
        val declIndex = lines.indexOfFirst {
            declRegex.containsMatchIn(it) && it.contains("useState()")
        }
        if (declIndex < 0) return null

        val original = lines[declIndex]
        val fixed = original.replace("useState()", "useState([])")
        if (fixed == original) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Initialize $varName as an empty array: useState() → useState([])",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = declIndex + 1,
            lineEnd = declIndex + 1,
            isDeterministic = true,
            confidence = 1.0
        )
    }
}
