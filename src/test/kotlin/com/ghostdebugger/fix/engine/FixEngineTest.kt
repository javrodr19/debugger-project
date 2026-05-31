package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixEngineTest : BasePlatformTestCase() {

    private fun issueAt(path: String) = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "t", description = "d", filePath = path, line = 1, ruleId = "AEG-NULL-001"
    )

    fun testFixDerivesAPlanFromTheDeterministicFixerAndAppliesIt() {
        val psi = myFixture.configureByText("A.kt", "val a = 1\nval b = 2\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val codeFix = CodeFix(
            id = "f1", issueId = "i1", description = "d", originalCode = "val b = 2",
            fixedCode = "val b = 3", filePath = vf.path, lineStart = 2, lineEnd = 2,
            isDeterministic = true, confidence = 1.0
        )
        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> codeFix })

        val result = engine.fix(issueAt(vf.path), vf, content)

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("val b = 3"))
    }

    fun testFixReturnsRejectedWhenNoDeterministicFixerApplies() {
        val psi = myFixture.configureByText("A.kt", "val a = 1\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> null })

        val result = engine.fix(issueAt(vf.path), vf, content)

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
    }
}
