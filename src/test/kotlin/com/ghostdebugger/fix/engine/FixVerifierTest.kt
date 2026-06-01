package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertTrue
import org.junit.Test

class FixVerifierTest {
    private fun issue(ruleId: String?, type: IssueType, line: Int) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = line, ruleId = ruleId
    )

    private val cast10 = issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 10)
    private val cast30 = issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 30)

    @Test fun acceptsWhenTargetResolvedAndNoRegression() {
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = emptyList())
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenTargetRuleCountUnchanged() {
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = listOf(cast10))
        assertTrue(d is VerifyDecision.Reject)
    }

    @Test fun rejectsWhenANewRuleAppears() {
        val newRule = issue("AEG-NULL-KT-001", IssueType.NULL_SAFETY, 5)
        // target cast resolved (0 < 1) but a different rule rose 0 -> 1
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = listOf(newRule))
        assertTrue(d is VerifyDecision.Reject)
    }

    @Test fun acceptsWhenUnchangedIssueLineShifts() {
        // Simulates InsertImport shifting an unrelated issue from line 20 -> 21.
        val async20 = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 20)
        val async21 = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 21)
        val d = FixVerifier().decide(
            cast10,
            baselineForFile = listOf(cast10, async20),
            candidateForFile = listOf(async21) // cast gone, async same count at a shifted line
        )
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun acceptsWhenOneOfTwoSameRuleIssuesResolved() {
        val d = FixVerifier().decide(
            cast10,
            baselineForFile = listOf(cast10, cast30),
            candidateForFile = listOf(cast30) // 2 -> 1
        )
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenTargetRuleNotInBaseline() {
        // Degenerate: target rule absent from baseline -> cannot be "resolved".
        val other = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 5)
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(other), candidateForFile = listOf(other))
        assertTrue(d is VerifyDecision.Reject)
    }
}
