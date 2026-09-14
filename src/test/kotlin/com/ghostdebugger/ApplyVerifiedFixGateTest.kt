package com.ghostdebugger

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

class ApplyVerifiedFixGateTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetPresenterForTest()
        super.tearDown()
    }

    fun `test gated fix never reaches the fix engine`() {
        val shown = AtomicBoolean(false)
        AegisCapabilityGate.setPresenterForTest { _, _ -> shown.set(true) }

        val engineRan = AtomicBoolean(false)
        val virtualFile = myFixture.configureByText("Sample.kt", "val x = 1\n").virtualFile
        val issue = Issue(
            id = "test-issue-1",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "Possible null dereference",
            description = "test issue",
            filePath = virtualFile.path,
            line = 1,
        )

        val job = AnalysisOrchestrator.getInstance(project).applyVerifiedFix(
            issue = issue,
            virtualFile = virtualFile,
            content = "val x = 1\n",
            baselineProvider = { emptyList() },
            fixVerified = { _, _, _, _ ->
                engineRan.set(true)
                FixApplyResult.Rejected("must not be called")
            },
        )
        runBlocking { job.join() }

        assertFalse("the fix engine must not run while FIX_APPLICATION is gated", engineRan.get())
        assertTrue("the user must be told why nothing happened", shown.get())
    }
}
