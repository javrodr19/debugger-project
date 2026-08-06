package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixPreviewUXTest : BasePlatformTestCase() {

    fun `test FixDiffGenerator produces unified hunks for line changes`() {
        val orig = "fun main() {\n    val x = null\n}"
        val fixed = "fun main() {\n    val x: String? = null\n}"

        val diff = FixDiffGenerator.generateDiff(
            filePath = "Main.kt",
            issueId = "AEG-NULL-001",
            description = "Fix nullability",
            originalContent = orig,
            fixedContent = fixed,
            isVerified = true
        )

        assertEquals("Main.kt", diff.filePath)
        assertTrue(diff.hunks.isNotEmpty())
        val lines = diff.hunks.flatMap { it.lines }
        assertTrue(lines.any { it.type == DiffLineType.ADDED && it.text.contains("val x: String?") })
        assertTrue(lines.any { it.type == DiffLineType.DELETED && it.text.contains("val x = null") })
    }

    fun `test BatchFixPreview computes multi-item diffs`() {
        val file = myFixture.configureByText("Main.kt", "val a = 1\nval b = 2")
        val issue = Issue(
            id = "issue-1",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "Test issue",
            description = "Fix line 1",
            filePath = file.virtualFile.path,
            line = 1,
            column = 1,
            codeSnippet = "val a = 1",
            sources = listOf(IssueSource.STATIC)
        )
        val plan = FixPlan(issue.id, listOf(ReplaceRange(0, 9, "val a = 10")))

        val result = BatchFixPreview.previewBatch(
            project = project,
            items = listOf(issue to plan),
            virtualFileMap = mapOf(file.virtualFile.path to (file.virtualFile to file.text))
        )

        assertEquals(1, result.totalFixable)
        assertEquals(0, result.totalSkipped)
        assertEquals(1, result.diffs.size)
        assertTrue(result.diffs[0].hunks.flatMap { it.lines }.any { it.text.contains("val a = 10") })
    }
}
