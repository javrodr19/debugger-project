package com.ghostdebugger.ai

import com.ghostdebugger.ai.prompts.PromptTemplates
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesPlanFixTest {
    private fun issue() = Issue(
        id = "ISSUE-7", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "Unsafe cast", description = "cast may fail", filePath = "A.kt", line = 4,
        ruleId = "AEG-CAST-KT-001"
    )

    @Test fun describesCatalogAndEmbedsIssueAndContent() {
        val p = PromptTemplates.planFix(issue(), "fun f(a: Any) = a as String\n", feedback = null)
        assertTrue(p.contains("replaceRange"))
        assertTrue(p.contains("insertImport"))
        assertTrue(p.contains("convertToSafeCast"))
        assertTrue(p.contains("ISSUE-7"))             // issueId for the envelope
        assertTrue(p.contains("AEG-CAST-KT-001"))     // rule identity
        assertTrue(p.contains("fun f(a: Any) = a as String")) // file content embedded
        assertFalse(p.contains("REJECTED"))           // no feedback section when feedback == null
    }

    @Test fun includesFeedbackSectionWhenProvided() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = "Fix did not resolve the target issue.")
        assertTrue(p.contains("REJECTED"))
        assertTrue(p.contains("Fix did not resolve the target issue."))
    }
}
