package com.ghostdebugger.analysis

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
