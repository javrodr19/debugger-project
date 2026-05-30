package com.ghostdebugger.inspections

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.mockk
import java.util.UUID

class AegisLocalInspectionTest : BasePlatformTestCase() {

    private fun issue(filePath: String, line: Int, ruleId: String): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "Unsafe property access",
        description = "value may be null",
        filePath = filePath,
        line = line,
        codeSnippet = "const x = obj.prop;",
        ruleId = ruleId,
        affectedNodes = emptyList()
    )

    fun testInspectionReportsProblems() {
        val service = GhostDebuggerService.getInstance(project)
        val virtualFile = myFixture.tempDirFixture.createFile("Test.ts", "const x = obj.prop;\nconst y = obj2.prop;\n")
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val filePath = virtualFile.path

        // Add 2 mock issues to the file path
        val issue1 = issue(filePath, 1, "AEG-NULL-001")
        val issue2 = issue(filePath, 2, "AEG-SYNTAX-001")
        service.updateIssues(listOf(issue1, issue2))

        val file = myFixture.file
        // Create the inspection instances
        val nullSafetyInspection = AegisNullSafetyInspection()
        val syntaxInspection = AegisSyntaxInspection()

        val manager = InspectionManager.getInstance(project)
        val session = mockk<LocalInspectionToolSession>(relaxed = true)
        
        // 1. Verify null safety inspection reports only null safety issues
        val holder1 = ProblemsHolder(manager, file, false)
        val visitor1 = nullSafetyInspection.buildVisitor(holder1, false, session)
        visitor1.visitFile(file)

        val results1 = holder1.results
        assertEquals(1, results1.size)
        assertTrue(results1[0].descriptionTemplate.contains("AEG-NULL-001"))
        assertTrue(results1[0].descriptionTemplate.contains("Unsafe property access"))

        // 2. Verify syntax inspection reports only syntax issues
        val holder2 = ProblemsHolder(manager, file, false)
        val visitor2 = syntaxInspection.buildVisitor(holder2, false, session)
        visitor2.visitFile(file)

        val results2 = holder2.results
        assertEquals(1, results2.size)
        assertTrue(results2[0].descriptionTemplate.contains("AEG-SYNTAX-001"))
        assertTrue(results2[0].descriptionTemplate.contains("Unsafe property access"))
    }
}
