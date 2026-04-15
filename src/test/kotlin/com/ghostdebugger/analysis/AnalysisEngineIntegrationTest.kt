package com.ghostdebugger.analysis

import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.SymbolExtractor
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnalysisEngineIntegrationTest {

    @Test
    fun `full project static scan produces expected issues from multiple analyzers`() = runTest {
        val rawFiles = listOf(
            FixtureFactory.parsedFile("/src/Nulls.tsx", "tsx", """
                function A() {
                  const [user, setUser] = useState(null);
                  return <div>{user.name}</div>;
                }
            """.trimIndent()),
            FixtureFactory.parsedFile("/src/State.tsx", "tsx", """
                function B() {
                  const [items, setItems] = useState();
                  return <div>{items.map(i => i)}</div>;
                }
            """.trimIndent()),
            FixtureFactory.parsedFile("/src/Async.ts", "ts", """
                fetch("/api").then(r => r.json());
            """.trimIndent()),
            FixtureFactory.parsedFile("/src/Complex.kt", "kt", """
                class Complex {
                    fun heavy(a: Int) {
                        if (a > 0) { if (a > 1) { if (a > 2) { if (a > 3) { if (a > 4) { if (a > 5) {
                            if (a > 6) { if (a > 7) { if (a > 8) { if (a > 9) { if (a > 10) {
                                println(a)
                            } } } } }
                        } } } } } }
                    }
                }
            """.trimIndent()),
            FixtureFactory.parsedFile("/src/C1.ts", "ts", "import { b } from './C2'; export const a = b;"),
            FixtureFactory.parsedFile("/src/C2.ts", "ts", "import { a } from './C1'; export const b = a;")
        )

        // 1. Extract symbols
        val extractor = SymbolExtractor()
        val parsedFiles = rawFiles.map { extractor.extract(it) }

        // 2. Resolve dependencies
        val resolver = DependencyResolver("/src")
        val dependencies = resolver.resolve(parsedFiles)

        // 3. Build graph
        val graphBuilder = GraphBuilder()
        val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)

        val ctx = AnalysisContext(
            graph = inMemoryGraph,
            project = io.mockk.mockk(relaxed = true),
            parsedFiles = parsedFiles
        )
        
        val engine = AnalysisEngine(
            settingsProvider = { GhostDebuggerSettings.State(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null }
        )
        
        val result = engine.analyze(ctx)
        
        println("Issues found: ${result.issues.size}")
        result.issues.forEach { println(" - ${it.type}: ${it.title} at ${it.filePath}\n   Desc: ${it.description}") }
        
        val types = result.issues.map { it.type }.toSet()
        assertTrue(types.contains(IssueType.NULL_SAFETY), "Should detect NULL_SAFETY")
        assertTrue(types.contains(IssueType.STATE_BEFORE_INIT), "Should detect STATE_BEFORE_INIT")
        assertTrue(types.contains(IssueType.UNHANDLED_PROMISE), "Should detect UNHANDLED_PROMISE")
        assertTrue(types.contains(IssueType.HIGH_COMPLEXITY), "Should detect HIGH_COMPLEXITY")
        assertTrue(types.contains(IssueType.CIRCULAR_DEPENDENCY), "Should detect CIRCULAR_DEPENDENCY")
        
        assertTrue(result.issues.size >= 5)
    }
}
