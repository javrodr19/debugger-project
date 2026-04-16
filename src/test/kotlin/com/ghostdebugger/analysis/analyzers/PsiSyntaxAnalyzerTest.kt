package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.graph.InMemoryGraph
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PsiSyntaxAnalyzerTest : BasePlatformTestCase() {
    private val analyzer = PsiSyntaxAnalyzer()

    fun testFlagsSyntaxErrorInKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "val classment = ")
        val virtualFile = psiFile.virtualFile
        val parsedFile = ParsedFile(
            virtualFile = virtualFile,
            path = virtualFile.path,
            extension = "kt",
            content = psiFile.text
        )
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )
        
        val issues = analyzer.analyze(context)
        
        assertNotEmpty(issues)
        assertTrue(issues.any { it.type == IssueType.SYNTAX_ERROR })
        assertEquals("AEG-SYNTAX-001", issues.first().ruleId)
    }

    fun testFlagsSyntaxErrorInJava() {
        val psiFile = myFixture.configureByText("Test.java", "public class { }")
        val virtualFile = psiFile.virtualFile
        val parsedFile = ParsedFile(
            virtualFile = virtualFile,
            path = virtualFile.path,
            extension = "java",
            content = psiFile.text
        )
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )
        
        val issues = analyzer.analyze(context)
        
        assertNotEmpty(issues)
        assertTrue(issues.any { it.type == IssueType.SYNTAX_ERROR })
    }

    fun testFlagsUnclosedBraceInKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "fun foo() {")
        val virtualFile = psiFile.virtualFile
        val parsedFile = ParsedFile(
            virtualFile = virtualFile,
            path = virtualFile.path,
            extension = "kt",
            content = psiFile.text
        )
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )
        
        val issues = analyzer.analyze(context)
        
        assertNotEmpty(issues)
        assertTrue(issues.any { it.type == IssueType.SYNTAX_ERROR })
    }

    fun testBinaryFileReturnsEmpty() {
        // Mock a situation where findFile might return null (binary or unknown)
        val virtualFile = myFixture.addFileToProject("test.bin", "binary content").virtualFile
        val parsedFile = ParsedFile(
            virtualFile = virtualFile,
            path = virtualFile.path,
            extension = "bin",
            content = "binary content"
        )
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )
        
        // PsiManager.findFile for .bin usually returns null or a non-PSI file
        val issues = analyzer.analyze(context)
        assertEmpty(issues)
    }

    fun testEmptyOnCleanKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "fun foo() = 42")
        val virtualFile = psiFile.virtualFile
        val parsedFile = ParsedFile(
            virtualFile = virtualFile,
            path = virtualFile.path,
            extension = "kt",
            content = psiFile.text
        )
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )
        
        val issues = analyzer.analyze(context)
        
        assertEmpty(issues)
    }
}
