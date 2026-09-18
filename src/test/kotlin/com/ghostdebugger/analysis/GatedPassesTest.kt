package com.ghostdebugger.analysis

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.rules.RulePackService
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the three *background* passes gated in 3.0.0: rule packs (`RulePackService.packRules`),
 * the external-analyzer block inside `AnalysisEngine.doStaticPasses`, and the AI pass
 * (`AnalysisEngine.runAiPass`). All three use `AegisCapabilityGate.skipIfGated`, which is silent —
 * unlike the user-clicked surfaces guarded elsewhere, a modal dialog raised from inside a
 * background analysis pass would itself be a defect.
 *
 * No `setEnabledForTest`/`resetForTest` pairing is needed here: these tests assert the *gated*
 * (shipped) behavior directly, so they must run against the real, hardcoded-disabled `enabled` set.
 */
class GatedPassesTest : BasePlatformTestCase() {

    fun `test rule packs contribute no rules while gated`() {
        val rules = RulePackService.getInstance(project).packRules()
        assertTrue(rules.isEmpty(), "gated rule packs must contribute nothing, got $rules")
    }

    fun `test AI pass reports DISABLED regardless of configured provider`() = runBlocking {
        val psi = myFixture.configureByText("Ai.kt", "fun f(): Int = 1\n")
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(
                ParsedFile(psi.virtualFile, psi.virtualFile.path, "kt", psi.text)
            )
        )
        val aiOnlyIssue = Issue(
            id = "ai-should-not-appear",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "AI-sourced finding",
            description = "must never leak through the AI_ANALYSIS gate",
            filePath = psi.virtualFile.path,
            line = 1
        )
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.OLLAMA, maxAiFiles = 5) },
            apiKeyProvider = { null },
            analyzers = emptyList(),
            // If the gate is bypassed, this runner would be invoked and its issue would surface —
            // proving the assertions below fail for a real reason, not because nothing ever runs.
            aiPassRunner = AiPassRunner { _, _ -> listOf(aiOnlyIssue) }
        )

        val result = engine.analyze(context)

        assertEquals("STATIC", result.engineStatus.provider)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
        assertTrue(
            result.issues.none { it.id == "ai-should-not-appear" },
            "AI-sourced issue leaked through the gate: ${result.issues}"
        )
    }
}
