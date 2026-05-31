package com.ghostdebugger.parser

import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * scanFiles() analyzes PRODUCTION code only. Test sources legitimately use patterns the analyzers
 * would otherwise flag as health issues (intentional downcasts in assertions, fixtures, reflection),
 * and on a real project they dominated the report. Excluding them here is a cross-cutting fix for
 * every analyzer, not just unsafe-cast.
 */
class FileScannerScopeTest : BasePlatformTestCase() {

    fun testScanFilesExcludesTestSources() {
        myFixture.addFileToProject("prod/Main.kt", "val a = 1")
        val testFile = myFixture.addFileToProject("tests/MainTest.kt", "val b = 2")
        PsiTestUtil.addSourceRoot(module, testFile.virtualFile.parent, /* isTestSource = */ true)

        val scanned = runReadAction { FileScanner(project).scanFiles() }.map { it.name }

        assertTrue("production file should be scanned; got $scanned", scanned.contains("Main.kt"))
        assertFalse("test-source file must be excluded; got $scanned", scanned.contains("MainTest.kt"))
    }
}
