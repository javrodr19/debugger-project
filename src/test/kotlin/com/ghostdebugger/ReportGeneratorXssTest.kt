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
        java.io.File("debug_report.html").writeText(html)
        
        // Assertions
        assertFalse(html.contains("<script>"), "HTML should not contain raw script tag")
        assertFalse(html.contains("<img"), "HTML should not contain raw img tag")
        
        assertTrue(html.contains("&lt;script&gt;"), "HTML should contain escaped script tag")
        assertTrue(html.contains("&lt;img"), "HTML should contain escaped img tag")
        assertTrue(html.contains("&quot;"), "HTML should contain escaped quotes")
        assertTrue(html.contains("&amp;"), "HTML should contain escaped ampersand")
        assertTrue(html.contains("&#39;"), "HTML should contain escaped single quote")
    }
}
