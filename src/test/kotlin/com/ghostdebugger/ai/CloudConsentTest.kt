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
 *
 * **This test cannot discriminate the consent bug in this environment, and plausibly not in CI
 * either.** [AnalysisOrchestrator.resolveAiService] sources its key from
 * `ApiKeyManager.getApiKey()`, which is unset here — no PasswordSafe credential, no
 * `OPENAI_API_KEY` env var — and a 3.0.0 read-only release has no reason to provision one in test
 * infrastructure. With no key, [AIServiceFactory.create]'s blank-key branch already returns null
 * before the consent branch is ever reached, so this test passes identically whether or not the
 * consent check exists. It is an integration smoke check on the orchestrator's public surface,
 * not the regression guard for this fix. The test that actually proves the fix — the one that
 * fails if the consent branch is removed — is `AIServiceFactoryTest`'s
 * `OPENAI with key but no cloud consent returns null`, which supplies the API key directly and
 * so isn't masked by the missing credential. Confirmed by temporarily reverting the consent
 * branch in `AIServiceFactory.create`: this test kept passing, while that one failed.
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
