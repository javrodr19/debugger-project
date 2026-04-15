package com.ghostdebugger.ai

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class OpenAIServiceTimeoutTest {
    
    @Test fun `suggestFix sets isDeterministic to false and confidence to 0_7`() {
        val svc = OpenAIService("sk-fake", "gpt-4o", "https://api.openai.com/v1", 1000, 3600)
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
        
        val parseMethod = OpenAIService::class.java.getDeclaredMethod("parseFixResponse", String::class.java, Issue::class.java, String::class.java)
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
