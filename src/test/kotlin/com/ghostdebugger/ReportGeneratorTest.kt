package com.ghostdebugger

import com.ghostdebugger.model.GraphMetadata
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeStatus
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.model.ProjectGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.LocalDateTime

class ReportGeneratorTest {

    private val fixedDate = LocalDateTime.of(2026, 5, 6, 12, 0, 0)
    private val gen = ReportGenerator(dateProvider = { fixedDate })

    private fun emptyGraph() = ProjectGraph(
        nodes = emptyList(),
        edges = emptyList(),
        metadata = GraphMetadata(
            projectName = "Sample",
            totalFiles = 0,
            totalIssues = 0,
            analysisTimestamp = 0L,
            healthScore = 100.0
        )
    )

    private fun graphWithOneError() = ProjectGraph(
        nodes = listOf(
            GraphNode(
                id = "n1",
                name = "Foo",
                filePath = "/repo/src/Foo.kt",
                type = NodeType.FILE,
                status = NodeStatus.ERROR,
                complexity = 3,
                issues = listOf(
                    Issue(
                        id = "i1",
                        type = IssueType.NULL_SAFETY,
                        severity = IssueSeverity.ERROR,
                        title = "Nullable 'x' accessed",
                        description = "Property 'x' is nullable and accessed without ?.",
                        filePath = "/repo/src/Foo.kt",
                        line = 5,
                        codeSnippet = "println(x.length)",
                        ruleId = "AEG-NULL-KT-001"
                    )
                )
            )
        ),
        edges = emptyList(),
        metadata = GraphMetadata(
            projectName = "Sample",
            totalFiles = 1,
            totalIssues = 1,
            analysisTimestamp = 0L,
            healthScore = 50.0
        )
    )

    @Test fun `output begins with DOCTYPE on column 0 — no leading whitespace`() {
        val html = gen.generateHTMLReport(emptyGraph())
        assertTrue(html.startsWith("<!DOCTYPE html>"), "Expected output to start with <!DOCTYPE html>; got:\n${html.take(80)}")
    }

    @Test fun `output has no line beginning with 4 or more spaces`() {
        val html = gen.generateHTMLReport(graphWithOneError())
        val offendingLines = html.lines().filter { it.startsWith("    ") }
        assertTrue(
            offendingLines.isEmpty(),
            "Expected no lines with leading 4+ spaces; got ${offendingLines.size} offending lines, first:\n${offendingLines.firstOrNull()}"
        )
    }

    @Test fun `error issue produces issue-card with error class`() {
        val html = gen.generateHTMLReport(graphWithOneError())
        assertTrue(html.contains("class=\"issue-card error\""), "Expected an issue-card.error block in output")
        assertTrue(html.contains("Nullable &#39;x&#39; accessed"), "Expected escaped issue title in output")
    }

    @Test fun `HTML escapes ampersands, angle brackets, and quotes in titles`() {
        val graph = graphWithOneError().copy(
            nodes = graphWithOneError().nodes.map { node ->
                node.copy(issues = node.issues.map { it.copy(title = "x < y && a > b \"hi\"") })
            }
        )
        val html = gen.generateHTMLReport(graph)
        assertTrue(html.contains("x &lt; y &amp;&amp; a &gt; b &quot;hi&quot;"), "Expected escaped title in output")
        assertFalse(html.contains("x < y && a > b \"hi\""), "Expected raw title NOT in output")
    }

    @Test fun `health score, error count, warning count appear in stat cards`() {
        val html = gen.generateHTMLReport(graphWithOneError())
        assertTrue(html.contains(">50%<") || html.contains(">50<"), "Expected 50% health score in stats")
        assertTrue(html.contains(">1<"), "Expected error count of 1 in stats")
    }

    @Test fun `fixed date provider produces deterministic output`() {
        val html1 = gen.generateHTMLReport(emptyGraph())
        val html2 = gen.generateHTMLReport(emptyGraph())
        assertEquals(html1, html2, "Same graph + fixed date should produce byte-identical output")
    }

    @Test fun `displayPath uses last 3 segments only`() {
        val graph = graphWithOneError().copy(
            nodes = graphWithOneError().nodes.map { it.copy(filePath = "/very/long/path/to/some/repo/src/main/kotlin/Foo.kt") }
        )
        val html = gen.generateHTMLReport(graph)
        assertTrue(html.contains("main/kotlin/Foo.kt") || html.contains("kotlin/Foo.kt"),
            "Expected last-3-segments display path in node card; got\n${html.lines().filter { it.contains("file-path") }.joinToString("\n")}")
    }
}
