package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineProvider
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisEngineStaticFirstTest {

    private fun settings(
        aiProvider: AIProvider = AIProvider.NONE,
        allowCloudUpload: Boolean = false,
        maxFilesToAnalyze: Int = 500,
        maxAiFiles: Int = 100
    ) = GhostDebuggerSettings.State(
        aiProvider = aiProvider,
        allowCloudUpload = allowCloudUpload,
        maxFilesToAnalyze = maxFilesToAnalyze,
        maxAiFiles = maxAiFiles
    )

    // A real AnalysisContext with one TSX file that triggers NullSafetyAnalyzer.
    private fun ctx(): AnalysisContext {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        return FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/A.tsx", "tsx", code))
        )
    }

    @Test
    fun `static analyzers run even when AI provider is NONE`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = { settings(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> error("AI should not run") }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY })
        assertEquals("STATIC", result.engineStatus.provider)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
    }

    @Test
    fun `static issues carry STATIC sources and providers`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = { settings() },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx())
        val staticHit = result.issues.first { it.type == IssueType.NULL_SAFETY }
        assertTrue(IssueSource.STATIC in staticHit.sources)
        assertTrue(EngineProvider.STATIC in staticHit.providers)
        assertEquals(1.0, staticHit.confidence)
    }

    @Test
    fun `static analyzers run before AI and both contribute when AI is ONLINE`() = runTest {
        val aiIssue = Issue(
            id = "ai-1",
            type = IssueType.MISSING_ERROR_HANDLING,
            severity = IssueSeverity.WARNING,
            title = "Catch-all missing",
            description = "AI-flagged",
            filePath = "/src/A.tsx",
            line = 2
        )
        val engine = AnalysisEngine(
            settingsProvider = {
                settings(aiProvider = AIProvider.OPENAI, allowCloudUpload = true)
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { _, _ -> listOf(aiIssue) }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY },
            "static issue missing")
        assertTrue(result.issues.any { it.type == IssueType.MISSING_ERROR_HANDLING },
            "AI issue missing")
        assertEquals(EngineStatus.ONLINE, result.engineStatus.status)
    }

    @Test
    fun `static analyzers run even when the AI pass throws`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = {
                settings(aiProvider = AIProvider.OPENAI, allowCloudUpload = true)
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { _, _ -> throw RuntimeException("network down") }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY })
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, result.engineStatus.status)
        assertEquals("OPENAI", result.engineStatus.provider)
    }
}
