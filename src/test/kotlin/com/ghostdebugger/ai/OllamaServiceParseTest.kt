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
    
}
