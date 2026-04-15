package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixApplicatorValidityTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply returns Rejected when syntax error is detected`() {
        val project = mockk<Project>(relaxed = true)
        val fix = CodeFix(
            id = "f1", issueId = "i1", description = "Test",
            originalCode = "val x = 1",
            fixedCode = "val x = ;;;",
            filePath = "/src/Test.kt",
            lineStart = 1, lineEnd = 1
        )

        // Mock the writer to simulate a syntax error detection
        val mockWriter = FixWriter { _, _ -> false }
        val applicator = FixApplicator(mockWriter)
        
        val result = applicator.apply(fix, project)
        
        assertTrue(result is FixApplyResult.Rejected)
        if (result is FixApplyResult.Rejected) {
            assertTrue(result.reason.contains("invalid code"))
        }
    }

    @Test
    fun `apply returns Success when writer succeeds`() {
        val project = mockk<Project>(relaxed = true)
        val fix = CodeFix(
            id = "f1", issueId = "i1", description = "Test",
            originalCode = "val x = 1",
            fixedCode = "val x = 2",
            filePath = "/src/Test.kt",
            lineStart = 1, lineEnd = 1
        )

        val mockWriter = FixWriter { _, _ -> true }
        val applicator = FixApplicator(mockWriter)
        
        val result = applicator.apply(fix, project)
        
        assertTrue(result is FixApplyResult.Success)
    }
}
