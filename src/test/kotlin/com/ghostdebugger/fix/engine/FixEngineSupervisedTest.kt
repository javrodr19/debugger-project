package com.ghostdebugger.fix.engine

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ProjectGraph
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FixEngineSupervisedTest : BasePlatformTestCase() {

    private fun issue() = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
    )

    /** Fake AIService that returns a scripted plan per attempt and records the feedback received. */
    private class FakeAI(private val plans: List<FixPlan?>) : AIService {
        val feedbacks = mutableListOf<String?>()
        private var i = 0
        override suspend fun detectIssues(filePath: String, fileContent: String, functions: List<com.ghostdebugger.model.FunctionSymbol>) = emptyList<Issue>()
        override suspend fun explainIssue(issue: Issue, codeSnippet: String) = ""
        override suspend fun explainSystem(graph: ProjectGraph) = ""
        override suspend fun proposeFixPlan(issue: Issue, fileContent: String, feedback: String?): FixPlan? {
            feedbacks += feedback
            return plans.getOrNull(i++)
        }
    }

    fun testAiRetriesWithFeedbackUntilGateAccepts() {
        val vf = myFixture.configureByText("A.kt", "fun f() {}\n").virtualFile
        val p1 = FixPlan("i1", listOf(InsertImport("a.X")))
        val p2 = FixPlan("i1", listOf(InsertImport("a.Y")))
        val applied = mutableListOf<FixPlan>()
        val results = ArrayDeque(listOf<FixApplyResult>(FixApplyResult.Rejected("nope-1"), FixApplyResult.Success))

        val engine = FixEngine(project, derivePlan = { _, _, _ -> null })  // no deterministic plan
        val ai = FakeAI(listOf(p1, p2))

        val result = runBlocking {
            engine.fixSupervised(
                issue(), vf, "content", baselineForFile = emptyList(), aiService = ai,
                reanalyze = { emptyList() },
                applyVerified = { plan -> applied += plan; results.removeFirst() },
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertEquals(listOf(p1, p2), applied)                       // both candidates were applied
        assertEquals(listOf(null, "nope-1"), ai.feedbacks)          // 2nd attempt got the 1st rejection
    }

    fun testReturnsRejectedWhenNoAiAndNoDeterministicPlan() {
        val vf = myFixture.configureByText("A.kt", "fun f() {}\n").virtualFile
        val engine = FixEngine(project, derivePlan = { _, _, _ -> null })
        val result = runBlocking {
            engine.fixSupervised(
                issue(), vf, "content", baselineForFile = emptyList(), aiService = null,
                reanalyze = { emptyList() },
                applyVerified = { FixApplyResult.Success },  // never called (no plan source)
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Rejected)
    }
}
