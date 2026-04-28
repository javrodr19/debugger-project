package com.ghostdebugger

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for Kotlin analyzer tests that need real type resolution.
 *
 * Uses [AegisKotlinStdlibProjectDescriptor] to load Kotlin stdlib into the light fixture,
 * which is required for the Analysis API to return real types instead of `KaErrorType`.
 * Without it, [analyze] silently returns no findings — see R3 in the V1.3 design spec.
 *
 * Subclasses provide test methods; this class provides the [analyze],
 * [expectFinding], and [expectNoFinding] helpers.
 */
abstract class AegisKotlinAnalysisTestCase : BasePlatformTestCase() {

    /**
     * The Kotlin Analysis API throws `ProhibitedAnalysisException` when `analyze { }` is
     * called from the EDT. Run test methods on a worker thread instead — `myFixture.*`
     * operations internally hop to EDT when needed, so this is safe for setup helpers.
     */
    override fun runInDispatchThread(): Boolean = false

    override fun getProjectDescriptor(): LightProjectDescriptor =
        AegisKotlinStdlibProjectDescriptor.INSTANCE

    /** Configures `Sample.kt` from [source], runs [analyzerFactory], returns the issues. */
    protected fun analyze(
        source: String,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ): List<Issue> {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        val pf = ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = source
        )
        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        return analyzerFactory().analyze(ctx)
    }

    protected fun expectFinding(
        source: String,
        ruleId: String,
        line: Int,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ) {
        val issues = analyze(source, analyzerFactory)
        val matching = issues.filter { it.ruleId == ruleId && it.line == line }
        assertTrue(
            "Expected at least one finding with ruleId=$ruleId on line $line; got: ${issues.map { it.ruleId to it.line }}",
            matching.isNotEmpty()
        )
    }

    protected fun expectNoFinding(
        source: String,
        ruleId: String,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ) {
        val issues = analyze(source, analyzerFactory).filter { it.ruleId == ruleId }
        assertTrue(
            "Expected no findings with ruleId=$ruleId; got: $issues",
            issues.isEmpty()
        )
    }
}
