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

    @Test fun listsTheFullOperationCatalogIncludingNewOps() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        // originals still present
        assertTrue(p.contains("replaceRange"))
        assertTrue(p.contains("convertToSafeCast"))
        // new ops from batches 1-4 are now exposed
        assertTrue(p.contains("wrapInSafeCall"))
        assertTrue(p.contains("surroundWithTryCatch"))
        assertTrue(p.contains("addExplicitConversion"))
        assertTrue(p.contains("removeRange"))
        assertTrue(p.contains("insertStatementAfter"))
    }

    @Test fun everyCatalogOpAppearsInThePrompt() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        com.ghostdebugger.fix.engine.FixOperationCatalog.serialNames().forEach { op ->
            assertTrue("prompt missing op: $op", p.contains(op))
        }
    }

    private fun cpxIssue() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
    )

    @Test fun complexityIssueGetsExtractMethodGuidance() {
        val p = PromptTemplates.planFix(cpxIssue(), "fun f() {}", feedback = null)
        assertTrue(p.contains("extract"))
        assertTrue(p.contains("replaceLines"))
        assertTrue(p.contains("insertLinesAfter"))
    }

    @Test fun nonComplexityIssueHasNoExtractMethodGuidance() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        assertFalse(p.contains("most complex function"))
    }
}
