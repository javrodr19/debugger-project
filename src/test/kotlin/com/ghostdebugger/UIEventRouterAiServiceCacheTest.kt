package com.ghostdebugger

import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The consent gate in [com.ghostdebugger.ai.AIServiceFactory] is only effective if
 * [UIEventRouter] actually re-consults it when settings change. Before this fix,
 * `aiService ?: resolveAiService()` cached the first resolved service for the life of the
 * router: flipping "Allow cloud upload" off after a service had already been resolved left the
 * stale (already-consented) service live until IDE restart. These tests exercise the cache
 * directly via [UIEventRouter.currentAiServiceForTest], using OLLAMA (which needs no API key)
 * so the cache mechanism itself — not the consent gate, covered by CloudConsentTest — is what's
 * under test.
 */
class UIEventRouterAiServiceCacheTest : BasePlatformTestCase() {

    private lateinit var original: GhostDebuggerSettings.State

    override fun setUp() {
        super.setUp()
        original = GhostDebuggerSettings.getInstance().snapshot()
    }

    override fun tearDown() {
        GhostDebuggerSettings.getInstance().loadState(original)
        super.tearDown()
    }

    fun `test resolved service is reused while settings are unchanged`() {
        GhostDebuggerSettings.getInstance().update {
            aiProvider = AIProvider.OLLAMA
            ollamaModel = "llama3"
        }
        val router = UIEventRouter.getInstance(project)
        val first = router.currentAiServiceForTest()
        val second = router.currentAiServiceForTest()
        assertNotNull(first)
        assertSame("an unchanged settings snapshot must not re-resolve", first, second)
    }

    fun `test changing settings invalidates the cached service`() {
        GhostDebuggerSettings.getInstance().update {
            aiProvider = AIProvider.OLLAMA
            ollamaModel = "llama3"
        }
        val router = UIEventRouter.getInstance(project)
        val first = router.currentAiServiceForTest()

        GhostDebuggerSettings.getInstance().update {
            ollamaModel = "mistral"
        }
        val second = router.currentAiServiceForTest()
        assertNotNull(second)
        assertNotSame("a changed settings snapshot must force re-resolution", first, second)
    }
}
