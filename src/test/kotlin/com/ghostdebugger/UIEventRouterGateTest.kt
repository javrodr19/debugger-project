package com.ghostdebugger

import com.ghostdebugger.bridge.UIEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UIEventRouterGateTest {

    @Test
    fun `fix surfaces map to FIX_APPLICATION`() {
        assertEquals(
            AegisCapability.FIX_APPLICATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.ApplyFixRequested("issue-1", "fix-1"))
        )
        assertEquals(
            AegisCapability.FIX_APPLICATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.FixRequested("issue-1", "node-1"))
        )
    }

    @Test
    fun `explanation surfaces map to AI_EXPLANATION`() {
        assertEquals(
            AegisCapability.AI_EXPLANATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.ExplainSystemRequested)
        )
    }

    @Test
    fun `impact analysis maps to JVM_DEPENDENCY_GRAPH`() {
        assertEquals(
            AegisCapability.JVM_DEPENDENCY_GRAPH,
            UIEventRouter.gatedCapabilityFor(UIEvent.ImpactRequested("node-1"))
        )
    }

    @Test
    fun `analysis, export and navigation stay ungated`() {
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.AnalyzeRequested))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.ExportReportRequested))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.NodeDoubleClicked("node-1")))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.DismissIssue("issue-1")))
    }
}
