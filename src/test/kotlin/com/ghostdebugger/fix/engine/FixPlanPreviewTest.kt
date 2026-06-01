package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixPlanPreviewTest : BasePlatformTestCase() {

    fun testRendersPlanAsBeforeAfterCodeFix() {
        val content = "fun f(): Int { return 1 }\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("return 1")
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 2")))
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
            title = "t", description = "", filePath = vf.path, line = 1, ruleId = "AEG-X"
        )

        val cf = FixPlanPreview.render(plan, project, vf, content, issue)!!

        assertEquals("i1", cf.issueId)
        assertEquals(content, cf.originalCode)
        assertTrue(cf.fixedCode, cf.fixedCode.contains("return 2"))
        assertFalse(cf.fixedCode.contains("return 1"))
        assertFalse(cf.isDeterministic)
    }

    fun testReturnsNullWhenPlanDoesNotApply() {
        val content = "fun f() {}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        // Offsets out of range -> ReplaceRange.toEdit returns null -> toEdits null -> render null.
        val plan = FixPlan("i1", listOf(ReplaceRange(9999, 10000, "x")))
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
            title = "t", description = "", filePath = vf.path, line = 1, ruleId = "AEG-X"
        )
        assertNull(FixPlanPreview.render(plan, project, vf, content, issue))
    }
}
