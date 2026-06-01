package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FixPlanApplicatorVerifyTest : BasePlatformTestCase() {

    private val target = Issue(
        id = "t", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
    )

    private fun planReplacing(needle: String, replacement: String, psiText: String): FixPlan {
        val start = psiText.indexOf(needle)
        return FixPlan("t", listOf(ReplaceRange(start, start + needle.length, replacement)))
    }

    fun testAcceptsWhenReanalysisShowsTargetResolved() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { emptyList() },              // candidate clean: resolved + no regression
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("return 2"))
    }

    fun testRejectsAndRevertsWhenTargetStillPresent() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { listOf(target) },           // target still detected: not resolved
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }

    fun testRejectsAndRevertsOnRegression() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)
        val newRule = target.copy(id = "n", ruleId = "AEG-NULL-KT-001")

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { listOf(newRule) },          // target gone but a new rule appears
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }

    fun testRejectsInvalidCandidateBeforeReanalysis() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 1 }}}", text) // unbalanced braces -> Tier-1 fail
        var reanalyzeCalled = false

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { reanalyzeCalled = true; emptyList() },
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertFalse("Tier-2 must not run when Tier-1 fails", reanalyzeCalled)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }
}
