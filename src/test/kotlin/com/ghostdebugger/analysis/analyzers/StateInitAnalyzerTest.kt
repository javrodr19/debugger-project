package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class StateInitAnalyzerTest {
    private val analyzer = StateInitAnalyzer()

    @Test
    fun `flags map on useState called without initial value`() {
        val code = """
            function List() {
              const [items, setItems] = useState();
              return items.map(i => <li key={i}>{i}</li>);
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/List.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.STATE_BEFORE_INIT }, issues.toString())
    }

    @Test
    fun `does not flag map on useState with empty array`() {
        val code = """
            function List() {
              const [items, setItems] = useState([]);
              return items.map(i => <li key={i}>{i}</li>);
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/List.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.STATE_BEFORE_INIT }, issues.toString())
    }
}
