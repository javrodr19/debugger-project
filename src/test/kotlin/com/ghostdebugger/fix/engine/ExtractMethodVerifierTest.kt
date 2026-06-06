package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtractMethodVerifierTest : BasePlatformTestCase() {
    private fun cpx() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
    )
    // original: one function f with 4 `if` -> complexity 5
    private val original = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
        "    if (a) {}\n    if (b) {}\n    if (c) {}\n    if (d) {}\n}\n"
    // candidate: f keeps 2 ifs + call (complexity 3); new g has 2 ifs (complexity 3)
    private val candidateGood = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
        "    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n\n" +
        "fun g(c: Boolean, d: Boolean) {\n    if (c) {}\n    if (d) {}\n}\n"

    private fun verifier(threshold: Int) = ExtractMethodVerifier(project, threshold)

    fun testAcceptsGenuineDecomposition() {
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    fun testRejectsWhenNoFunctionAdded() {
        // candidate just renames a call — same function set, f unchanged
        val candidate = original.replace("if (d) {}", "if (e) {}")
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidate, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    fun testRejectsWhenSourceNotOverThreshold() {
        // genuine decomposition, but threshold 10 > source's original complexity 5
        val d = verifier(threshold = 10).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
        assertTrue((d as VerifyDecision.Reject).reason, d.reason.contains("threshold"))
    }

    fun testRejectsWhenExtractedFunctionNotSimplerThanSource() {
        // f drops 5 -> 3, but g has 4 ifs (complexity 5) = original source complexity -> not simpler
        val candidate = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
            "    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n\n" +
            "fun g(c: Boolean, d: Boolean) {\n    if (c) {}\n    if (d) {}\n    if (c) {}\n    if (d) {}\n}\n"
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidate, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    fun testRejectsOnRegression() {
        // genuine decomposition, but candidate analysis surfaces a new other-rule issue
        val newIssue = Issue(
            id = "n", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "x", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-NULL-001"
        )
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, listOf(newIssue))
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }
}
