package com.ghostdebugger.analysis

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

@Tag("perf")
class AnalysisEnginePerfSmoke {
    @Test fun `medium fixture completes within 15s on CI baseline`() {
        // Generating 50 files for the medium fixture
        val files = (1..50).map {
            FixtureFactory.parsedFile("/src/File${it}.kt", "kt", """
                package src
                class File$it {
                    fun doWork() {
                        val x = null
                        println(x?.toString())
                    }
                }
            """.trimIndent())
        }
        val ctx = FixtureFactory.context(files)
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null }
        )

        val elapsed = measureTimeMillis {
            runBlocking { engine.analyze(ctx) }
        }

        println("PERF medium=${elapsed}ms")
        assertTrue(elapsed < 15_000, "medium fixture exceeded 15s budget: ${elapsed}ms")
    }
}

/**
 * Kotlin Analysis API perf entry (V1.3 acceptance criterion #5).
 *
 * Kept as a separate class from [AnalysisEnginePerfSmoke] (JUnit 5) because this
 * test requires [AegisKotlinAnalysisTestCase] for the off-EDT runner and stdlib
 * fixture. Mixing JUnit 5 (@Test) with JUnit 3 (fun testXxx) in the same class
 * would break both runners.
 */
class KotlinAnalysisPerfSmoke : AegisKotlinAnalysisTestCase() {

    fun testKotlinAnalysisLatencyOn100SyntheticFunctions() {
        val sample = (1..100).joinToString("\n") { i ->
            "fun fn$i(x: String?): Int = x?.length ?: 0"
        }
        val vf = myFixture.configureByText("Bulk.kt", sample).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = sample
        )
        val ctx = com.ghostdebugger.model.AnalysisContext(
            graph = com.ghostdebugger.graph.InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        val analyzer = com.ghostdebugger.analysis.analyzers.KotlinNullSafetyAnalyzer()
        val start = System.nanoTime()
        analyzer.analyze(ctx)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Use TestCase.assertTrue (inherited from BasePlatformTestCase), not JUnit 5's static import.
        junit.framework.TestCase.assertTrue(
            "KotlinNullSafetyAnalyzer took ${elapsedMs}ms on 100 synthetic functions; expected < 2000ms",
            elapsedMs < 2_000
        )
    }
}
