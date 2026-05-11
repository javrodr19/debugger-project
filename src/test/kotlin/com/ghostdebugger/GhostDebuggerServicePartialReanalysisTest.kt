package com.ghostdebugger

import org.junit.jupiter.api.Test

/**
 * V1.5 structural smoke test: pins the location of `reanalyzeFile` after the
 * GhostDebuggerService → AnalysisOrchestrator extraction. Full behavioral coverage of
 * targeted re-analysis lives in `GhostDebuggerServiceReanalyzeDependentsTest`, which runs
 * under `BasePlatformTestCase` with a real PSI fixture.
 *
 * Pre-V1.5 this test reflected into `GhostDebuggerService.reanalyzeFile`. Post-V1.5 the
 * method lives on `AnalysisOrchestrator`.
 */
class GhostDebuggerServicePartialReanalysisTest {

    @Test
    fun `reanalyzeFile is present on AnalysisOrchestrator after V1_5 split`() {
        // Throws NoSuchMethodException if the method drifts off AnalysisOrchestrator.
        val method = AnalysisOrchestrator::class.java.getDeclaredMethod("reanalyzeFile", String::class.java)
        method.isAccessible = true
    }
}
