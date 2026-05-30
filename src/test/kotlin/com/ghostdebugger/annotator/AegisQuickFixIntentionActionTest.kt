package com.ghostdebugger.annotator

import com.ghostdebugger.fix.AegisQuickFixIntentionAction
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert

class AegisQuickFixIntentionActionTest : BasePlatformTestCase() {

    fun testQuickFixIntentionAction() {
        val virtualFile = myFixture.tempDirFixture.createFile("Test.ts", "const x = obj.prop;\n")
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val filePath = virtualFile.path

        val issue = Issue(
            id = "issue-123",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Unsafe null property access",
            description = "obj might be null",
            filePath = filePath,
            line = 1
        )

        val fix = CodeFix(
            id = "fix-123",
            issueId = "issue-123",
            description = "Use safe optional chaining",
            originalCode = "const x = obj.prop;",
            fixedCode = "const x = obj?.prop;",
            filePath = filePath,
            lineStart = 1,
            lineEnd = 1,
            isDeterministic = true
        )

        val customWriter = com.ghostdebugger.fix.FixWriter { codeFix, proj ->
            var succeeded = false
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj, "Test Fix", null, Runnable {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile)
                if (document != null) {
                    val startOffset = document.getLineStartOffset(codeFix.lineStart - 1)
                    val endOffset = document.getLineEndOffset(codeFix.lineEnd - 1)
                    document.replaceString(startOffset, endOffset, codeFix.fixedCode)
                    com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(document)
                    succeeded = true
                }
            })
            succeeded
        }
        val customApplicator = com.ghostdebugger.fix.FixApplicator(customWriter)
        val action = AegisQuickFixIntentionAction(issue, fix, customApplicator)

        // Verify metadata
        Assert.assertEquals("Aegis: Fix 'Unsafe null property access'", action.text)
        Assert.assertEquals("Aegis Debug Quick-Fixes", action.familyName)
        Assert.assertTrue(action.isAvailable(project, myFixture.editor, myFixture.file))
        Assert.assertFalse(action.startInWriteAction())

        // Invoke the quick-fix
        action.invoke(project, myFixture.editor, myFixture.file)

        // Verify the document text has been updated
        myFixture.checkResult("const x = obj?.prop;\n")
    }
}
