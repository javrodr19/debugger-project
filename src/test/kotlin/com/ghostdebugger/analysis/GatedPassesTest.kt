package com.ghostdebugger.analysis

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
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
 * The rule-pack and AI-pass tests here only assert the *gated* (shipped) behavior, against the
 * real, hardcoded-disabled `enabled` set — the "capability enabled" direction for each is already
 * covered elsewhere (`RulePackServiceTest` for RULE_PACKS; `AnalysisEngineStaticFirstTest` and
 * others for AI_ANALYSIS, per Task 5's enable-override fix-up), so it is not duplicated here.
 * EXTERNAL_ANALYZERS had neither direction covered anywhere before the `externalAnalyzerRunner`
 * seam below existed, so both tests for it live in this file: one proves the default (gated) state
 * contributes nothing, the other proves the seam and the gate are actually wired to the same place
 * by lifting the gate with `setEnabledForTest` and confirming the issue then appears. Without the
 * second test, a mutation that broke or reordered the guard (e.g. always returning `emptyList()`,
 * gate check independent of the seam) would pass this suite undetected.
 *
 * `setEnabledForTest` is called per-test, never in a class-level `setUp`, because only the
 * "enabled" external-analyzer test wants the gate lifted — the other three tests need the shipped
 * default left alone. [AegisCapabilityGate.resetForTest] in `tearDown` runs unconditionally after
 * every test, which is harmless for the three that never touched the override.
 */
class GatedPassesTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetForTest()
        super.tearDown()
    }

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

    private fun externalAnalyzerContext(): AnalysisContext {
        val psi = myFixture.configureByText("Ext.kt", "fun g(): Int = 2\n")
        return AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(
                ParsedFile(psi.virtualFile, psi.virtualFile.path, "kt", psi.text)
            )
        )
    }

    private fun engineWithFakeExternalAnalyzer(externalOnlyIssue: Issue) = AnalysisEngine(
        settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
        apiKeyProvider = { null },
        analyzers = emptyList(),
        // If the gate is bypassed, this runner would be invoked and its issue would surface —
        // the same proof shape as the AI-pass test above, mirrored for EXTERNAL_ANALYZERS via the
        // seam added alongside this test (AnalysisEngine had no way to inject a fake loader before).
        externalAnalyzerRunner = { _ -> listOf(externalOnlyIssue) }
    )

    fun `test external analyzer issue never appears while EXTERNAL_ANALYZERS is gated`() = runBlocking {
        val context = externalAnalyzerContext()
        val externalOnlyIssue = Issue(
            id = "external-should-not-appear",
            type = IssueType.CUSTOM_RULE,
            severity = IssueSeverity.WARNING,
            title = "External-SDK finding",
            description = "must never leak through the EXTERNAL_ANALYZERS gate",
            filePath = context.parsedFiles.single().path,
            line = 1
        )

        val result = engineWithFakeExternalAnalyzer(externalOnlyIssue).analyze(context)

        assertTrue(
            result.issues.none { it.id == "external-should-not-appear" },
            "external-analyzer issue leaked through the gate: ${result.issues}"
        )
    }

    fun `test external analyzer issue leaks through when EXTERNAL_ANALYZERS is enabled`() = runBlocking {
        AegisCapabilityGate.setEnabledForTest(setOf(AegisCapability.EXTERNAL_ANALYZERS))
        val context = externalAnalyzerContext()
        val externalOnlyIssue = Issue(
            id = "external-should-appear",
            type = IssueType.CUSTOM_RULE,
            severity = IssueSeverity.WARNING,
            title = "External-SDK finding",
            description = "proves the seam and the gate are wired to the same place",
            filePath = context.parsedFiles.single().path,
            line = 1
        )

        val result = engineWithFakeExternalAnalyzer(externalOnlyIssue).analyze(context)

        assertTrue(
            result.issues.any { it.id == "external-should-appear" },
            "external-analyzer issue did not reach the result with the capability enabled: ${result.issues}"
        )
    }
}
