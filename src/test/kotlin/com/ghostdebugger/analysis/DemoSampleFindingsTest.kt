package com.ghostdebugger.analysis

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.analysis.analyzers.AsyncFlowAnalyzer
import com.ghostdebugger.analysis.analyzers.CircularDependencyAnalyzer
import com.ghostdebugger.analysis.analyzers.ComplexityAnalyzer
import com.ghostdebugger.analysis.analyzers.KotlinRedundantLetAnalyzer
import com.ghostdebugger.analysis.analyzers.KotlinUnsafeCastAnalyzer
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.analysis.analyzers.StateInitAnalyzer
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.SymbolExtractor
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Staleness guard for the shipped demo at `samples/aegis-demo/`.
 *
 * This test reads the exact files a reviewer opens from disk — never an inline copy of their
 * source — and runs the real analyzers (via [AnalysisEngine], the same engine `Ctrl+Alt+G`
 * drives) over them. If a future edit to a sample stops tripping the rule `DEMO.md` documents for
 * it, this test fails here, not silently in front of a reviewer following the walkthrough.
 *
 * Extends [AegisKotlinAnalysisTestCase] (a real light IDE project with Kotlin stdlib on the
 * classpath) rather than reusing [AnalysisEngineIntegrationTest]'s mocked-`Project` pattern,
 * because the two Kotlin rules under test resolve types through the real Kotlin Analysis API and
 * silently produce zero findings against a mocked `Project`.
 */
class DemoSampleFindingsTest : AegisKotlinAnalysisTestCase() {

    private val sampleSrcDir = File("samples/aegis-demo/src")

    private fun readSample(name: String): String = File(sampleSrcDir, name).readText()

    fun testDemoSamplesProduceAllSevenDocumentedFindings() {
        // TS/JS samples: the analyzers that read them only ever touch ParsedFile content/lines/
        // path, never `virtualFile`, so FixtureFactory's mocked VirtualFile is fine — real PSI
        // is not needed for the regex-based analyzers.
        val tsxTsFiles = listOf(
            "/samples/aegis-demo/src/NullSafety.tsx" to "tsx",
            "/samples/aegis-demo/src/StateInit.tsx" to "tsx",
            "/samples/aegis-demo/src/AsyncFlow.tsx" to "tsx",
            "/samples/aegis-demo/src/Complexity.ts" to "ts",
            "/samples/aegis-demo/src/cycleA.ts" to "ts",
            "/samples/aegis-demo/src/cycleB.ts" to "ts"
        ).map { (path, ext) ->
            FixtureFactory.parsedFile(path, ext, readSample(File(path).name))
        }

        // Kotlin sample: the two Kotlin rules need a REAL project-bound KtFile, so this one goes
        // through the light fixture rather than FixtureFactory's mocked VirtualFile.
        val kotlinContent = readSample("KotlinSample.kt")
        val ktVirtualFile = myFixture.configureByText("KotlinSample.kt", kotlinContent).virtualFile
        val kotlinRaw = ParsedFile(
            virtualFile = ktVirtualFile,
            path = ktVirtualFile.path,
            extension = "kt",
            content = kotlinContent
        )

        val extractor = SymbolExtractor(project)
        val parsedFiles = (tsxTsFiles + kotlinRaw).map { extractor.extract(it) }

        val dependencies = DependencyResolver().resolve(parsedFiles)
        val graph = GraphBuilder().build(parsedFiles, dependencies)
        val ctx = AnalysisContext(graph = graph, project = project, parsedFiles = parsedFiles)

        // CompilationErrorAnalyzer is deliberately left out of this list: it drives the real
        // highlighting daemon, which is exercised elsewhere (CompilationErrorAnalyzerHarvestTest)
        // and would make this staleness guard depend on heavier IDE machinery than the seven
        // rule-id assertions need. The two Kotlin rules' own KaErrorType gates already refuse to
        // fire on code that doesn't resolve cleanly, so a sample that failed to compile would
        // fail this test anyway, just via a different symptom (missing rule id, not exclusion).
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            analyzers = listOf(
                NullSafetyAnalyzer(),
                KotlinUnsafeCastAnalyzer(),
                KotlinRedundantLetAnalyzer(),
                StateInitAnalyzer(),
                AsyncFlowAnalyzer(),
                CircularDependencyAnalyzer(),
                ComplexityAnalyzer(thresholdProvider = { 10 })
            )
        )

        val result = runBlocking { engine.analyzeStaticOnly(ctx) }

        val ruleIds = result.issues.mapNotNull { it.ruleId }.toSet()
        val expected = setOf(
            "AEG-NULL-001",
            "AEG-STATE-001",
            "AEG-ASYNC-001",
            "AEG-CPX-001",
            "AEG-CYCLE-001",
            "AEG-CAST-KT-001",
            "AEG-REDUNDANT-LET-KT-001"
        )
        val missing = expected - ruleIds
        assertTrue(
            "Demo sample(s) no longer trigger: $missing. Full rule ids seen: $ruleIds. " +
                "Issues: ${result.issues.map { "${it.ruleId}@${it.filePath}:${it.line}" }}",
            missing.isEmpty()
        )

        // The known duplicate reporting DEMO.md documents: StateInit.tsx's bare `useState()` also
        // matches NullSafetyAnalyzer's USE_STATE_NULL_REGEX (its null/undefined literal group is
        // optional), so the same line reports under both AEG-STATE-001 and AEG-NULL-001. Pinned
        // here so a change to either regex is a deliberate, visible decision rather than a silent
        // behavior change that leaves DEMO.md wrong.
        val stateInitRuleIds = result.issues
            .filter { it.filePath.endsWith("StateInit.tsx") }
            .mapNotNull { it.ruleId }
            .toSet()
        assertTrue(
            "Expected StateInit.tsx to report under both AEG-STATE-001 and AEG-NULL-001 " +
                "(the documented duplicate); got: $stateInitRuleIds",
            stateInitRuleIds.containsAll(setOf("AEG-STATE-001", "AEG-NULL-001"))
        )
    }
}
