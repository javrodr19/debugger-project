package com.ghostdebugger.analysis

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.analysis.analyzers.AIAnalyzer
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AnalysisEngineOllamaDelegationTest {

    /**
     * The OLLAMA branch must push all per-file work through AIAnalyzer,
     * not its own inline Semaphore loop. We prove that by injecting an
     * AIAnalyzer-shaped fake via the aiPassRunner seam and asserting
     * the Ollama branch exercises the shared path.
     */

    private class FakeService(val counter: AtomicInteger) : AIService {
        override suspend fun detectIssues(filePath: String, fileContent: String): List<Issue> {
            counter.incrementAndGet()
            return listOf(
                Issue(
                    id = UUID.randomUUID().toString(),
                    type = IssueType.NULL_SAFETY,
                    severity = IssueSeverity.ERROR,
                    title = "fake",
                    description = "fake",
                    filePath = filePath,
                    line = 1,
                    codeSnippet = "",
                    affectedNodes = listOf(filePath)
                )
            )
        }
        override suspend fun explainIssue(issue: Issue, codeSnippet: String) = "explained"
        override suspend fun suggestFix(issue: Issue, codeSnippet: String) =
            CodeFix(UUID.randomUUID().toString(), issue.id, "", "", "", issue.filePath, 1, 1, false, 0.7)
        override suspend fun explainSystem(graph: ProjectGraph) = "summary"
    }

    @Test fun `OLLAMA branch routes through AIAnalyzer once per file`() = runTest {
        val counter = AtomicInteger(0)
        val files = listOf(
            com.ghostdebugger.testutil.FixtureFactory.parsedFile("/a.ts", "ts", "const a = null;"),
            com.ghostdebugger.testutil.FixtureFactory.parsedFile("/b.ts", "ts", "const b = null;"),
            com.ghostdebugger.testutil.FixtureFactory.parsedFile("/c.ts", "ts", "const c = null;")
        )
        val ctx = com.ghostdebugger.testutil.FixtureFactory.context(files)

        val eng = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.OLLAMA, maxAiFiles = 5) },
            apiKeyProvider   = { null },
            analyzers = emptyList(),
            aiPassRunner = AiPassRunner { ctxIn, _ ->
                AIAnalyzer(
                    service = FakeService(counter),
                    concurrency = AIAnalyzer.DEFAULT_CONCURRENCY_LOCAL,
                    labelPrefix = "Ollama: "
                ).analyze(ctxIn)
            }
        )

        val result = eng.analyze(ctx)
        assertEquals(3, counter.get(), "AIAnalyzer should call detectIssues once per file")
        assertTrue(result.issues.size >= 3)
    }

    @Test fun `OLLAMA maxAiFiles=0 still produces DISABLED`() = runTest {
        val eng = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.OLLAMA, maxAiFiles = 0) },
            apiKeyProvider   = { null }
        )
        val ctx = com.ghostdebugger.testutil.FixtureFactory.context(emptyList())
        val result = eng.analyze(ctx)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
    }

    @Test fun `DEFAULT_CONCURRENCY constants match spec`() {
        assertEquals(3, AIAnalyzer.DEFAULT_CONCURRENCY_CLOUD)
        assertEquals(4, AIAnalyzer.DEFAULT_CONCURRENCY_LOCAL)
    }
}
