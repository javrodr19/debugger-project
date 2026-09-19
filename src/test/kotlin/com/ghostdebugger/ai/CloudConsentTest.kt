package com.ghostdebugger.ai

import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `settings.allowCloudUpload` gates every path that can construct an OpenAI service. The gate
 * lives in [AIServiceFactory.create] — the single chokepoint both [AnalysisOrchestrator] and
 * `UIEventRouter` route through — so this test exercises it via the orchestrator's resolver as
 * a representative caller, not because the orchestrator has its own copy of the check.
 */
class CloudConsentTest : BasePlatformTestCase() {

    private lateinit var original: GhostDebuggerSettings.State

    override fun setUp() {
        super.setUp()
        original = GhostDebuggerSettings.getInstance().snapshot()
    }

    override fun tearDown() {
        GhostDebuggerSettings.getInstance().loadState(original)
        super.tearDown()
    }

    fun `test OpenAI is not constructed without cloud consent`() {
        GhostDebuggerSettings.getInstance().update {
            aiProvider = AIProvider.OPENAI
            allowCloudUpload = false
        }
        assertNull(
            "no OpenAI service may be created while Allow cloud upload is off",
            AnalysisOrchestrator.getInstance(project).resolveAiServiceForTest()
        )
    }
}
