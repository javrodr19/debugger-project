package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Integration: registered KotlinTypeMismatchFixer.generatePlan → AddExplicitConversion → applyVerified
 * applies the widening conversion. Target supplied directly (a type mismatch is a compile error, so the
 * static pipeline's early pass shadows AEG-TYPE-KT-001 in single-file re-analysis) and reanalyze stubbed
 * (gate verdict covered by 2b/2c-ii-a). PSI-only fixer → BasePlatformTestCase + Unconfined.
 */
class TypeMismatchBreadthIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerWidensThroughTheEngine() {
        val code = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "t1", type = IssueType.COMPILATION_ERROR, severity = IssueSeverity.ERROR,
            title = "Type mismatch on 'y'",
            description = "Declared type is not assignable from the initializer's type. Declared: Long. Initializer: Int.",
            filePath = vf.path, line = 2, ruleId = "AEG-TYPE-KT-001"
        )
        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains("val y: Long = n.toLong()"))
    }
}
