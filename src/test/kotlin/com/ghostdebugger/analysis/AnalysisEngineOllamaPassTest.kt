package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnalysisEngineOllamaPassTest {

    private fun engine(
        provider: AIProvider = AIProvider.OLLAMA,
        maxAiFiles: Int = 5
    ) = AnalysisEngine(
        settingsProvider = { GhostDebuggerSettings.State(aiProvider = provider, maxAiFiles = maxAiFiles) },
        apiKeyProvider   = { null }
        // aiPassRunner default — not reached by OLLAMA branch
    )

    @Test fun `OLLAMA maxAiFiles=0 produces DISABLED`() = runTest {
        val eng = engine(maxAiFiles = 0)
        val ctx = com.ghostdebugger.testutil.FixtureFactory.context(emptyList())
        val result = eng.analyze(ctx)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
    }
}
