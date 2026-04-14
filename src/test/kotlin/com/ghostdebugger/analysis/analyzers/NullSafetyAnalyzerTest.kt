package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class NullSafetyAnalyzerTest {
    private val analyzer = NullSafetyAnalyzer()

    @Test
    fun `flags direct property access on variable initialized as null`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/A.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.NULL_SAFETY }, "expected NULL_SAFETY issue in ${'$'}issues")
    }

    @Test
    fun `does not flag access guarded by null check`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              if (user) {
                return <div>{user.name}</div>;
              }
              return null;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/B.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY }, "did not expect NULL_SAFETY, got ${'$'}issues")
    }
}
