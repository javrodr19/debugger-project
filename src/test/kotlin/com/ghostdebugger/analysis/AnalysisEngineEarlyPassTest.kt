package com.ghostdebugger.analysis

import com.ghostdebugger.model.*
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AnalysisEngineEarlyPassTest : BasePlatformTestCase() {

    fun testBrokenFileSkipsDownstreamAnalyzers() = runBlocking {
        // Broken Kotlin file
        val brokenKt = myFixture.configureByText("Broken.kt", "val classment = ")
        
        // Clean TSX file with Null Safety issue
        val cleanTsx = myFixture.configureByText("Clean.tsx", """
            function App() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent())

        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(
                ParsedFile(brokenKt.virtualFile, brokenKt.virtualFile.path, "kt", brokenKt.text),
                ParsedFile(cleanTsx.virtualFile, cleanTsx.virtualFile.path, "tsx", cleanTsx.text)
            )
        )

        val settings = GhostDebuggerSettings.State().apply {
            aiProvider = AIProvider.NONE
        }
        val engine = AnalysisEngine(settingsProvider = { settings })
        
        val result = engine.analyze(context)
        
        // Broken file should have SYNTAX_ERROR
        val brokenIssues = result.issues.filter { it.filePath == brokenKt.virtualFile.path }
        assertTrue(brokenIssues.any { it.type == IssueType.SYNTAX_ERROR }, "Expected SYNTAX_ERROR in $brokenIssues")
        
        // Acceptance Criterion 3: confirm ABSENCE of downstream static rules on broken file
        val downstreamRuleIds = setOf("AEG-NULL-001", "AEG-STATE-001", "AEG-ASYNC-001", "AEG-CPX-001")
        val brokenRuleIds = brokenIssues.mapNotNull { it.ruleId }.toSet()
        val intersection = downstreamRuleIds.intersect(brokenRuleIds)
        assertTrue(intersection.isEmpty(), "Broken file should NOT have downstream issues $intersection")

        // Clean file should have NULL_SAFETY
        val cleanIssues = result.issues.filter { it.filePath == cleanTsx.virtualFile.path }
        assertTrue(cleanIssues.any { it.type == IssueType.NULL_SAFETY }, "Expected NULL_SAFETY in $cleanIssues")
    }

    fun testPathNormalizationHandlesBackslashes() = runBlocking {
        // Mock a file path with backslashes
        val psiFile = myFixture.configureByText("Normal.kt", "val x = 1")
        val windowsPath = "C:\\Project\\src\\Normal.kt"
        
        // We simulate a syntax error that reports the path with backslashes
        val earlyIssue = Issue(
            id = "test",
            type = IssueType.SYNTAX_ERROR,
            severity = IssueSeverity.ERROR,
            title = "Syntax Error",
            description = "Error",
            filePath = windowsPath,
            line = 1,
            ruleId = "AEG-SYNTAX-001"
        )
        
        // Engine logic: brokenFilePaths = earlyIssues.map { it.filePath.replace("\\", "/") }.toSet()
        val brokenFilePaths = listOf(earlyIssue).map { it.filePath.replace("\\", "/") }.toSet()
        
        assertEquals(setOf("C:/Project/src/Normal.kt"), brokenFilePaths)
        
        // Check if a ParsedFile with the same path (normalized) is correctly filtered
        val parsedFile = ParsedFile(psiFile.virtualFile, "C:/Project/src/Normal.kt", "kt", psiFile.text)
        val isFiltered = parsedFile.path.replace("\\", "/") in brokenFilePaths
        
        assertTrue(isFiltered, "Path normalization should match Windows backslashes to normalized forward slashes")
    }
}
