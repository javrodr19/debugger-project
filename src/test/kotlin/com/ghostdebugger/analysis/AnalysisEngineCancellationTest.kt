package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnalysisEngineCancellationTest {

    @Test
    fun `checkCanceled propagates cancellation through analyzer loop`() {
        val files = listOf(
            FixtureFactory.parsedFile("/src/Nulls.tsx", "tsx", "const [u, setU] = useState(null); return u.name;")
        )
        val ctx = FixtureFactory.context(files)
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null }
        )
        
        val indicator = object : ProgressIndicator {
            override fun checkCanceled() {
                throw ProcessCanceledException()
            }
            override fun start() {}
            override fun stop() {}
            override fun isRunning(): Boolean = true
            override fun cancel() {}
            override fun isCanceled(): Boolean = true
            override fun setText(text: String?) {}
            override fun getText(): String? = null
            override fun setText2(text: String?) {}
            override fun getText2(): String? = null
            override fun getFraction(): Double = 0.0
            override fun setFraction(fraction: Double) {}
            override fun pushState() {}
            override fun popState() {}
            override fun isModal(): Boolean = false
            override fun getModalityState(): com.intellij.openapi.application.ModalityState = com.intellij.openapi.application.ModalityState.NON_MODAL
            override fun setModalityProgress(modalityProgress: ProgressIndicator?) {}
            override fun isIndeterminate(): Boolean = false
            override fun setIndeterminate(indeterminate: Boolean) {}
            override fun isPopupWasShown(): Boolean = false
            override fun isShowing(): Boolean = false
        }

        assertThrows(ProcessCanceledException::class.java) {
            runBlocking { engine.analyze(ctx, indicator) }
        }
    }

    @Test
    fun `runOne propagates ProcessCanceledException thrown from analyzer body`() {
        // Pre-V1.4.1: AnalysisEngine.runOne caught (e: Exception) and returned emptyList(),
        // swallowing PCE thrown from inside the analyzer.analyze() call. Engine returned a
        // partial result; user clicked Cancel, the engine kept running.
        val pceAnalyzer = object : Analyzer {
            override val name = "test-pce-thrower"
            override val ruleId = "TEST-PCE-001"
            override val defaultSeverity = IssueSeverity.ERROR
            override val description = "throws PCE on analyze() to verify rethrow path"
            override fun analyze(context: AnalysisContext): List<Issue> =
                throw ProcessCanceledException()
        }

        val files = listOf(FixtureFactory.parsedFile("/src/x.kt", "kt", "fun foo() {}"))
        val ctx = FixtureFactory.context(files)
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            analyzers = listOf(pceAnalyzer)
        )

        assertThrows(ProcessCanceledException::class.java) {
            runBlocking { engine.analyze(ctx) }
        }
    }
}
