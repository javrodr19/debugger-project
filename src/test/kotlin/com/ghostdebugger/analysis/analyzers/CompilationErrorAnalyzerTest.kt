package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.graph.InMemoryGraph
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CompilationErrorAnalyzerTest : BasePlatformTestCase() {
    private val analyzer = CompilationErrorAnalyzer()

    fun testFlagsTypeMismatchInKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "val x: Int = \"string\"")
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
        assertTrue(issues.any { it.type == IssueType.COMPILATION_ERROR })
        assertEquals("AEG-COMPILE-001", issues.first().ruleId)
    }

    fun testFlagsUnresolvedReferenceInKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "val x = doesNotExist()")
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
        assertTrue(issues.any { it.type == IssueType.COMPILATION_ERROR })
    }

    fun testCombinedParseAndSemanticError() {
        // Line 1 has syntax error, Line 3 has semantic error (type mismatch)
        val code = """
            val classment = 
            
            val x: Int = "string"
        """.trimIndent()
        val psiFile = myFixture.configureByText("Test.kt", code)
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
        
        val syntaxAnalyzer = PsiSyntaxAnalyzer()
        val compileAnalyzer = CompilationErrorAnalyzer()
        
        val syntaxIssues = syntaxAnalyzer.analyze(context)
        val compileIssues = compileAnalyzer.analyze(context)
        
        assertNotEmpty(syntaxIssues)
        assertNotEmpty(compileIssues)
        
        // Assert different rule IDs
        assertTrue(syntaxIssues.any { it.ruleId == "AEG-SYNTAX-001" })
        assertTrue(compileIssues.any { it.ruleId == "AEG-COMPILE-001" })
        
        // Assert different fingerprints (RuleID:Path:Line)
        val fingerprints = (syntaxIssues + compileIssues).map { it.fingerprint() }
        assertEquals(fingerprints.distinct().size, fingerprints.size, "Fingerprints should be unique even on the same file")
    }

    fun testEmptyOnCleanKotlin() {
        val psiFile = myFixture.configureByText("Test.kt", "val x: Int = 42")
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
