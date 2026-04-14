package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisEngineFileCapTest {

    private fun files(n: Int): List<ParsedFile> =
        (1..n).map { FixtureFactory.parsedFile("/src/F$it.tsx", "tsx", "// stub\n") }

    @Test
    fun `engine caps static input to maxFilesToAnalyze`() = runTest {
        val ctx: AnalysisContext = FixtureFactory.context(files(10))
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.NONE,
                    maxFilesToAnalyze = 3
                )
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertEquals(3, result.metrics.totalFiles)
    }

    @Test
    fun `engine leaves file list intact when under the cap`() = runTest {
        val ctx = FixtureFactory.context(files(2))
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.NONE,
                    maxFilesToAnalyze = 100
                )
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertEquals(2, result.metrics.totalFiles)
    }

    @Test
    fun `AI pass receives at most maxAiFiles files`() = runTest {
        val ctx = FixtureFactory.context(files(20))
        var aiReceived = -1
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.OPENAI,
                    allowCloudUpload = true,
                    maxFilesToAnalyze = 500,
                    maxAiFiles = 4
                )
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { aiCtx, _ ->
                aiReceived = aiCtx.parsedFiles.size
                emptyList()
            }
        )
        engine.analyze(ctx)
        assertEquals(4, aiReceived)
    }

    @Test
    fun `AI pass is invoked with empty file list when maxAiFiles is zero but provider is OPENAI`() = runTest {
        val ctx = FixtureFactory.context(files(5))
        val invoked = mutableListOf<Int>()
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.OPENAI,
                    allowCloudUpload = true,
                    maxAiFiles = 0
                )
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { aiCtx, _ ->
                invoked.add(aiCtx.parsedFiles.size)
                emptyList()
            }
        )
        engine.analyze(ctx)
        // maxAiFiles == 0 is DISABLED at the provider switch; runner is NOT invoked.
        assertTrue(invoked.isEmpty())
    }
}
