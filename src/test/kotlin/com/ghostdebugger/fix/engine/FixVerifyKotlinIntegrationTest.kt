package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import kotlin.coroutines.CoroutineContext

/**
 * End-to-end Tier-2: real analyzer detection -> ConvertToSafeCast candidate -> transient-document
 * re-analysis -> Accept. Runs off the EDT (AegisKotlinAnalysisTestCase) so the Analysis API is legal.
 *
 * EDT hops use an IntelliJ-native dispatcher ([intellijEdtDispatcher]) backed by
 * ApplicationManager.invokeLater so WriteCommandAction runs in a write-safe context.
 * Dispatchers.Swing (kotlinx-coroutines-swing) dispatches via SwingUtilities.invokeLater which
 * IntelliJ's TransactionGuard marks as write-unsafe; the IntelliJ-native dispatcher avoids this.
 *
 * Sample code uses a nullable return type (`String?`) so ConvertToSafeCast produces
 * `?: return null` rather than `?: throw IllegalStateException(...)`. The latter triggers a
 * spurious AEG-COMPILE-001 (Unresolved reference: IllegalStateException) from the IntelliJ
 * daemon in the test's light project, which only has kotlin-stdlib but no full mock JDK
 * resolved through the Analysis API. The `?: return null` fallback needs no external
 * references and produces clean analysis, letting the Tier-2 gate correctly accept the fix.
 */
class FixVerifyKotlinIntegrationTest : AegisKotlinAnalysisTestCase() {

    /**
     * A coroutine dispatcher that routes work through ApplicationManager.invokeLater with the
     * default (NON_MODAL) modality state. Unlike Dispatchers.Swing (which uses
     * SwingUtilities.invokeLater), this is write-safe under IntelliJ's TransactionGuard, allowing
     * WriteCommandAction to run correctly from a coroutine continuation on the EDT.
     */
    private val intellijEdtDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ApplicationManager.getApplication().invokeLater(block, ModalityState.defaultModalityState())
        }
    }

    fun testTransientReanalysisAcceptsRealSafeCastFix() {
        // Nullable return type: ConvertToSafeCast produces `?: return null` (no stdlib reference
        // needed) rather than `?: throw IllegalStateException(...)` which triggers a spurious
        // AEG-COMPILE-001 in the test's light project due to limited JDK resolution.
        val code = "fun f(a: Any): String? { return a as String }\n"
        val psi = myFixture.configureByText("A.kt", code) as KtFile
        val vf = psi.virtualFile

        // 1. Baseline: detect the real unsafe-cast issue from the original document (off-EDT).
        val baseline = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }
        val target = baseline.first { it.ruleId == "AEG-CAST-KT-001" }

        // 2. Build the Phase-2a ConvertToSafeCast plan from the detected `as` keyword offset.
        val text = runReadAction { myFixture.getDocument(psi).text }
        val asOffset = text.indexOf(" as ") + 1
        val plan = FixPlan(target.id, listOf(ConvertToSafeCast(asOffset)))

        // 3. Apply + verify with the real single-file re-analysis. The intellijEdtDispatcher is
        //    write-safe (unlike Dispatchers.Swing which uses SwingUtilities.invokeLater) so
        //    WriteCommandAction inside applyVerified is satisfied. The EDT hop is real; the
        //    re-analysis between the two write actions runs on the test (non-EDT) thread.
        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = baseline,
                reanalyze = { SingleFileStaticReanalysis(project).issuesFor(vf) },
                edtContext = intellijEdtDispatcher,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertTrue(after, after.contains("as? String"))
    }
}
