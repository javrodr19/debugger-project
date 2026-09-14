package com.ghostdebugger

import com.intellij.openapi.project.Project
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AegisCapabilityGateTest {

    @AfterTest
    fun restorePresenter() {
        AegisCapabilityGate.resetPresenterForTest()
    }

    @Test
    fun `no capability is enabled in this release`() {
        AegisCapability.entries.forEach { capability ->
            assertFalse(
                AegisCapabilityGate.isEnabled(capability),
                "$capability must ship disabled in 3.0.0"
            )
        }
    }

    @Test
    fun `blockIfGated reports the capability and returns true`() {
        val shown = mutableListOf<AegisCapability>()
        AegisCapabilityGate.setPresenterForTest { _, capability -> shown += capability }

        val blocked = AegisCapabilityGate.blockIfGated(null as Project?, AegisCapability.FIX_APPLICATION)

        assertTrue(blocked, "a gated capability must tell its caller to abort")
        assertEquals(listOf(AegisCapability.FIX_APPLICATION), shown)
    }

    @Test
    fun `skipIfGated never presents anything`() {
        val shown = mutableListOf<AegisCapability>()
        AegisCapabilityGate.setPresenterForTest { _, capability -> shown += capability }

        val skipped = AegisCapabilityGate.skipIfGated(AegisCapability.AI_ANALYSIS)

        assertTrue(skipped, "a gated capability must tell its background caller to skip")
        assertTrue(shown.isEmpty(), "a background pass must never raise a modal dialog")
    }

    @Test
    fun `message carries the label, the roadmap sentence and the reason`() {
        val message = AegisCapabilityGate.messageFor(AegisCapability.FIX_APPLICATION)

        assertContains(message, AegisCapability.FIX_APPLICATION.label)
        assertContains(message, "This feature will be built further when there's time.")
        assertContains(message, AegisCapability.FIX_APPLICATION.why)
    }

    @Test
    fun `every capability declares a non-blank label and reason`() {
        AegisCapability.entries.forEach { capability ->
            assertTrue(capability.label.isNotBlank(), "$capability has no label")
            assertTrue(capability.why.isNotBlank(), "$capability has no reason")
        }
    }
}
