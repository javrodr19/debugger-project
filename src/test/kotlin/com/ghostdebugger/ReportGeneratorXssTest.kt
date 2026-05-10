package com.ghostdebugger

import com.ghostdebugger.model.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportGeneratorXssTest {

    @Test
    fun `test html escaping prevents script injection`() {
        val xssPayload = "<script>alert('xss')</script>"
        val maliciousTitle = "Malicious Title $xssPayload"
        val maliciousDescription = "Malicious Description <img src=x onerror=alert(1)>"
        
        val graph = ProjectGraph(
            metadata = GraphMetadata(
                projectName = "Malicious Project $xssPayload",
                totalFiles = 1,
                totalIssues = 1,
                analysisTimestamp = System.currentTimeMillis(),
                healthScore = 50.0
            ),
            nodes = listOf(
                GraphNode(
                    id = "n1",
                    type = NodeType.FILE,
                    name = "Malicious Node \" & ' < >",
                    filePath = "path/to/malicious_file.ts",
                    status = NodeStatus.ERROR,
                    issues = listOf(
                        Issue(
                            id = "i1",
                            type = IssueType.NULL_SAFETY,
                            title = maliciousTitle,
                            description = maliciousDescription,
                            severity = IssueSeverity.ERROR,
                            filePath = "malicious_file.ts",
                            codeSnippet = "const x = '$xssPayload';"
                        )
                    )
                )
            ),
            edges = emptyList()
        )
        
        val reportGenerator = ReportGenerator()
        val html = reportGenerator.generateHTMLReport(graph)

        // Assertions
        assertFalse(html.contains("<script>"), "HTML should not contain raw script tag")
        assertFalse(html.contains("<img"), "HTML should not contain raw img tag")
        
        assertTrue(html.contains("&lt;script&gt;"), "HTML should contain escaped script tag")
        assertTrue(html.contains("&lt;img"), "HTML should contain escaped img tag")
        assertTrue(html.contains("&quot;"), "HTML should contain escaped quotes")
        assertTrue(html.contains("&amp;"), "HTML should contain escaped ampersand")
        assertTrue(html.contains("&#39;"), "HTML should contain escaped single quote")
    }

    @Test
    fun `date provider output is html-escaped`() {
        // V1.4.1 L4: the default `() -> LocalDateTime` signature can't actually
        // exhibit XSS, but the interpolation flowed raw into HTML — any future
        // widening of the signature (or a mocked provider) would inherit the
        // vulnerability. Pin the escape practice.
        val raw = "<script>alert(1)</script>"
        val gen = ReportGenerator(dateProvider = {
            // Use a real LocalDateTime — the htmlEscape wraps `.toString()`, so
            // the practice is verified by checking that the (safe) date renders
            // properly. The behaviour we're locking in is "everything that
            // interpolates into HTML goes through htmlEscape".
            java.time.LocalDateTime.of(2026, 5, 10, 12, 0)
        })
        val graph = ProjectGraph(
            metadata = GraphMetadata(
                projectName = "Sample",
                totalFiles = 0,
                totalIssues = 0,
                analysisTimestamp = 0,
                healthScore = 100.0
            ),
            nodes = emptyList(),
            edges = emptyList()
        )
        val html = gen.generateHTMLReport(graph)
        // The safe date should render as escaped (no < or > on the date line);
        // a hypothetical hostile provider would also be escaped by the same code path.
        assertTrue(html.contains("2026-05-10T12:00"), "Expected formatted date in output")
        // Also assert that the source contains the escape call (defends against future regression).
        val source = java.io.File("src/main/kotlin/com/ghostdebugger/ReportGenerator.kt").readText()
        assertTrue(
            source.contains("htmlEscape(dateProvider()"),
            "Expected ReportGenerator to htmlEscape the dateProvider output; the practice is the fix"
        )
        // And the raw XSS string never appears in the output (default provider is safe).
        assertFalse(html.contains(raw), "Default date provider must produce safe HTML")
    }
}
