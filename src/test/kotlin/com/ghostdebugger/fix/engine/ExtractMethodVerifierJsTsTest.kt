package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtractMethodVerifierJsTsTest : BasePlatformTestCase() {
    private fun cpx() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.ts", line = 1, ruleId = "AEG-CPX-001"
    )
    // f: 4 if -> complexity 5
    private val original = "function f(a, b, c, d) {\n    if (a) {}\n    if (b) {}\n    if (c) {}\n    if (d) {}\n}\n"
    // f: 2 if + call -> 3 ; g: 2 if -> 3
    private val candidateGood = "function f(a, b, c, d) {\n    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n" +
        "function g(c, d) {\n    if (c) {}\n    if (d) {}\n}\n"

    private fun verifier(threshold: Int) =
        ExtractMethodVerifier(project, threshold, JsTsPerFunctionComplexity::measure)

    fun testAcceptsGenuineJsTsDecomposition() {
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    fun testRejectsWhenSourceNotOverThreshold() {
        val d = verifier(threshold = 10).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
        assertTrue((d as VerifyDecision.Reject).reason, d.reason.contains("threshold"))
    }
}
