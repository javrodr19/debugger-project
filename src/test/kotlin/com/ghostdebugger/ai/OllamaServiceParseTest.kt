package com.ghostdebugger.ai

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class OllamaServiceParseTest {
    // Testing the private parse methods by exposing them via reflection or using public methods if possible.
    // Since they are private, we can test via reflection for unit testing purposes.

    @Test fun `parseFixResponse sets isDeterministic to false and confidence to 0_7`() {
        val svc = OllamaService("http://localhost", "model")
        val issue = Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Test",
            description = "Test desc",
            filePath = "/path.ts",
            line = 10,
            codeSnippet = "const a = null;",
            affectedNodes = emptyList()
        )
        
        val parseMethod = OllamaService::class.java.getDeclaredMethod("parseFixResponse", String::class.java, Issue::class.java, String::class.java)
        parseMethod.isAccessible = true
        
        val rawResponse = """
            EXPLANATION: I added optional chaining.
            ```typescript
            const a = b?.c;
            ```
        """.trimIndent()
        
        val fix = parseMethod.invoke(svc, rawResponse, issue, "const a = b.c;") as com.ghostdebugger.model.CodeFix
        
        assertFalse(fix.isDeterministic)
        assertEquals(0.7, fix.confidence)
        assertEquals("I added optional chaining.", fix.description)
        assertEquals("const a = b?.c;", fix.fixedCode)
    }
    
    @Test fun `parseDetectIssuesResponse parses valid JSON array inside text`() {
        val svc = OllamaService("http://localhost", "model")
        val rawResponse = """
            Here is the analysis:
            [
              {
                "type": "NULL_SAFETY",
                "severity": "ERROR",
                "title": "Null warning",
                "description": "May be null",
                "line": 5
              }
            ]
        """.trimIndent()
        
        val parseMethod = OllamaService::class.java.getDeclaredMethod("parseDetectIssuesResponse", String::class.java, String::class.java, String::class.java)
        parseMethod.isAccessible = true
        
        @Suppress("UNCHECKED_CAST")
        val issues = parseMethod.invoke(svc, rawResponse, "/path.ts", "1\n2\n3\n4\n5\n6\n7") as List<Issue>
        
        assertEquals(1, issues.size)
        assertEquals(IssueType.NULL_SAFETY, issues[0].type)
        assertEquals(IssueSeverity.ERROR, issues[0].severity)
        assertEquals("Null warning", issues[0].title)
        assertEquals(5, issues[0].line)
    }
}
