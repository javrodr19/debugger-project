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
        assertTrue(issues.any { it.type == IssueType.NULL_SAFETY }, "expected NULL_SAFETY issue in $issues")
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
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY }, "did not expect NULL_SAFETY, got $issues")
    }

    @Test
    fun `does not flag access only present inside a mid-line line comment`() {
        // V1.4.1 M1: pre-fix this would flag because the comment-skip only checked
        // line-leading // and *. doStuff(); // user.name now correctly skipped.
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              doStuff(); // user.name would be unsafe but is just docs
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/MidComment.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY },
            "Mid-line // user.name comment should not flag; got $issues")
    }

    @Test
    fun `does not flag access only present inside a mid-line block comment`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              const x = 1; /* user.name would crash */
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/BlockComment.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY },
            "/* user.name */ block comment should not flag; got $issues")
    }

    @Test
    fun `does not flag access only present inside a string literal`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              console.log("debug: user.name lookup");
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/StringLit.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY },
            "user.name inside a string literal should not flag; got $issues")
    }

    @Test
    fun `flags code access when same line also contains the var in a string literal`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              log("user.name"); return user.name;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Mixed.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.NULL_SAFETY },
            "Expected the code access to flag even though the line also contains a string-literal user.name; got $issues")
    }

    @Test
    fun `scales to many null-state vars without quadratic blowup`() {
        // V1.4.1 P1: combined alternation regex makes the scan O(N) instead of O(N × M).
        val varDefs = (1..50).joinToString("\n") { "let v$it = null;" }
        val accesses = (1..1000).joinToString("\n") { "console.log(v${(it % 50) + 1}.foo);" }
        val code = "$varDefs\n$accesses"
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/perf.ts", "ts", code))
        )
        val started = System.currentTimeMillis()
        val issues = analyzer.analyze(ctx)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 1000, "Expected < 1s for 1000 accesses × 50 vars; took ${elapsed}ms")
        assertTrue(issues.isNotEmpty(), "Expected some issues; got 0")
    }
}
