package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class AsyncFlowAnalyzerTest {
    private val analyzer = AsyncFlowAnalyzer()

    @Test
    fun `flags setInterval inside useEffect without cleanup`() {
        val code = """
            useEffect(() => {
              setInterval(() => doTick(), 1000);
            }, []);
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Timer.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.MEMORY_LEAK }, issues.toString())
    }

    @Test
    fun `does not flag setInterval with clearInterval cleanup`() {
        val code = """
            useEffect(() => {
              const id = setInterval(() => doTick(), 1000);
              return () => clearInterval(id);
            }, []);
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Timer.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.MEMORY_LEAK }, issues.toString())
    }
}
