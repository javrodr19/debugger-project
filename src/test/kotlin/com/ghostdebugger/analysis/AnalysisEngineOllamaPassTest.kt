package com.ghostdebugger.analysis

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * AI_ANALYSIS is gated in 3.0.0 (Task 5's `AnalysisEngine.runAiPass` guard). Without lifting it,
 * `maxAiFiles=0` still produces DISABLED, but for the wrong reason — the outer gate short-circuits
 * before `runOllamaPass`'s own `maxAiFiles` check ever runs, silently losing coverage of that
 * branch. Lifting AI_ANALYSIS here restores the original meaning; [AegisCapabilityGate.resetForTest]
 * undoes it after every test.
 */
class AnalysisEngineOllamaPassTest {

    @BeforeEach
    fun enableAiAnalysisForTest() {
        AegisCapabilityGate.setEnabledForTest(setOf(AegisCapability.AI_ANALYSIS))
    }

    @AfterEach
    fun resetGate() {
        AegisCapabilityGate.resetForTest()
    }

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
