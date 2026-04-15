package com.ghostdebugger.analysis

import com.ghostdebugger.model.*
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.testutil.FixtureFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class AnalysisEngineParallelStaticPassTest {

    @Test
    fun `static analyzers run in parallel`() = runBlocking {
        val latch = CountDownLatch(3)
        val startTimes = Collections.synchronizedList(mutableListOf<Long>())
        
        val analyzer1 = object : Analyzer {
            override val name = "A1"
            override val ruleId = "R1"
            override val defaultSeverity = IssueSeverity.INFO
            override val description = "D1"
            override fun analyze(context: AnalysisContext): List<Issue> {
                startTimes.add(System.currentTimeMillis())
                latch.countDown()
                latch.await(5, TimeUnit.SECONDS)
                return emptyList()
            }
        }
        
        val analyzer2 = object : Analyzer {
            override val name = "A2"
            override val ruleId = "R2"
            override val defaultSeverity = IssueSeverity.INFO
            override val description = "D2"
            override fun analyze(context: AnalysisContext): List<Issue> {
                startTimes.add(System.currentTimeMillis())
                latch.countDown()
                latch.await(5, TimeUnit.SECONDS)
                return emptyList()
            }
        }
        
        val analyzer3 = object : Analyzer {
            override val name = "A3"
            override val ruleId = "R3"
            override val defaultSeverity = IssueSeverity.INFO
            override val description = "D3"
            override fun analyze(context: AnalysisContext): List<Issue> {
                startTimes.add(System.currentTimeMillis())
                latch.countDown()
                latch.await(5, TimeUnit.SECONDS)
                return emptyList()
            }
        }

        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            analyzers = listOf(analyzer1, analyzer2, analyzer3)
        )
        
        val parsedFiles = listOf(FixtureFactory.parsedFile("test.kt", "kt", "content"))
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = io.mockk.mockk(relaxed = true),
            parsedFiles = parsedFiles
        )
        
        engine.analyze(context, null)
        
        assertEquals(3, startTimes.size)
        val minStart = startTimes.minOrNull() ?: 0L
        val maxStart = startTimes.maxOrNull() ?: 0L
        assertTrue(maxStart - minStart < 1000, "Analyzers did not run in parallel: diff=${maxStart - minStart}ms")
    }

    @Test
    fun `one analyzer throwing does not prevent others from producing results`() = runBlocking {
        val analyzer1 = object : Analyzer {
            override val name = "A1"
            override val ruleId = "R1"
            override val defaultSeverity = IssueSeverity.INFO
            override val description = "D1"
            override fun analyze(context: AnalysisContext): List<Issue> {
                throw RuntimeException("Boom")
            }
        }
        
        val analyzer2 = object : Analyzer {
            override val name = "A2"
            override val ruleId = "R2"
            override val defaultSeverity = IssueSeverity.INFO
            override val description = "D2"
            override fun analyze(context: AnalysisContext): List<Issue> {
                return listOf(Issue(
                    id = "i2",
                    type = IssueType.NULL_SAFETY,
                    title = "Issue 2",
                    description = "D",
                    severity = IssueSeverity.WARNING,
                    filePath = "f2"
                ))
            }
        }

        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            analyzers = listOf(analyzer1, analyzer2)
        )
        
        val parsedFiles = listOf(FixtureFactory.parsedFile("test.kt", "kt", "content"))
        val context = AnalysisContext(
            graph = InMemoryGraph(),
            project = io.mockk.mockk(relaxed = true),
            parsedFiles = parsedFiles
        )
        
        val result = engine.analyze(context, null)
        
        assertEquals(1, result.issues.size)
        assertEquals("Issue 2", result.issues[0].title)
    }
}
