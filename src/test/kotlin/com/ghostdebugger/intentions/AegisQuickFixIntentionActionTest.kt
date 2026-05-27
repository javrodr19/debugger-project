package com.ghostdebugger.intentions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AegisQuickFixIntentionActionTest : BasePlatformTestCase() {

    @Test
    fun testIntentionNotAvailableWithoutIssues() {
        val action = AegisQuickFixIntentionAction()
        val psiFile = myFixture.configureByText("Test.kt", "fun main() { val x = 42 }")
        myFixture.editor.caretModel.moveToOffset(5)

        val element = psiFile.findElementAt(5)
        assertNotNull(element)
        assertFalse(action.isAvailable(project, myFixture.editor, element!!))
    }

    @Test
    fun testIntentionAvailableWithFixableIssue() {
        val action = AegisQuickFixIntentionAction()
        val fileContent = """
            fun test() {
                val x: String? = null
                val len = x.length
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("Test.kt", fileContent)
        val line3Offset = fileContent.indexOf("val len = x.length") + 2
        myFixture.editor.caretModel.moveToOffset(line3Offset)

        val service = GhostDebuggerService.getInstance(project)
        val issue = Issue(
            id = "issue-123",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Nullable 'x' accessed without a null check",
            description = "Expression 'x' has a nullable type at this access.",
            filePath = psiFile.virtualFile.path,
            line = 3,
            sources = listOf(IssueSource.STATIC),
            ruleId = "AEG-NULL-001" // NullSafetyFixer matches this ruleId
        )

        service.updateIssues(listOf(issue))

        val element = psiFile.findElementAt(line3Offset)
        assertNotNull(element)

        assertTrue(action.isAvailable(project, myFixture.editor, element!!))
        assertEquals("Aegis Debug: Fix 'Nullable 'x' accessed without a null check'", action.text)
    }
}
