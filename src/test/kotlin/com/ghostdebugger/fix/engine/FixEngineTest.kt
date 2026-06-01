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

    fun testFixVerifiedRejectsWhenNoDeterministicFixer() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val issue = com.ghostdebugger.model.Issue(
            id = "i", type = com.ghostdebugger.model.IssueType.ARCHITECTURE,
            severity = com.ghostdebugger.model.IssueSeverity.WARNING,
            title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-NO-FIXER-999"
        )

        val result = kotlinx.coroutines.runBlocking {
            FixEngine(project).fixVerified(
                issue, vf, content, baselineForFile = listOf(issue),
                reanalyze = { emptyList() },
                edtContext = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is com.ghostdebugger.fix.FixApplyResult.Rejected)
    }

    fun testFixVerifiedAppliesAndVerifiesAnInjectedPlan() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val issue = com.ghostdebugger.model.Issue(
            id = "i", type = com.ghostdebugger.model.IssueType.NULL_SAFETY,
            severity = com.ghostdebugger.model.IssueSeverity.WARNING,
            title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
        )
        // CodeFix uses lineStart/lineEnd (1-based line numbers). lineStart=1, lineEnd=1 replaces the
        // entire first line (without trailing newline) with fixedCode. fixedCode must be the full
        // replacement line so that "return 2" appears in the result.
        val engine = FixEngine(
            project = project,
            deriveCodeFix = { _, _, _ ->
                com.ghostdebugger.model.CodeFix(
                    id = "f", issueId = issue.id, description = "d",
                    originalCode = "fun f(): Int { return 1 }",
                    fixedCode = "fun f(): Int { return 2 }",
                    filePath = "A.kt", lineStart = 1, lineEnd = 1
                )
            },
        )

        val result = kotlinx.coroutines.runBlocking {
            engine.fixVerified(
                issue, vf, content, baselineForFile = listOf(issue),
                reanalyze = { emptyList() },  // candidate clean -> resolved + no regression
                edtContext = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is com.ghostdebugger.fix.FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("return 2"))
    }
}
