package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplexityVerifierTest {
    private fun issue(rule: String, type: IssueType = IssueType.HIGH_COMPLEXITY) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING, title = "t", description = "",
        filePath = "A.kt", line = 1, ruleId = rule
    )
    private val target = issue("AEG-CPX-001")

    @Test fun acceptsWhenComplexityStrictlyDecreasesAndNoRegression() {
        // original has two `if`s, candidate one → estimateComplexity drops (functionCount=1)
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, baselineForFile = listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = emptyList()
        )
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenComplexityDidNotDecrease() {
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}", candidateContent = "if (a) {}",
            candidateForFile = emptyList()
        )
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    @Test fun rejectsWhenANewOtherRuleIssueAppears() {
        // complexity dropped, but a new AEG-NULL-001 issue appeared → regression
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = listOf(issue("AEG-NULL-001", IssueType.NULL_SAFETY))
        )
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    @Test fun ignoresTheComplexityRuleItselfInTheRegressionCheck() {
        // a re-detected AEG-CPX-001 in candidate must not count as a regression
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = listOf(issue("AEG-CPX-001"))
        )
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }
}
