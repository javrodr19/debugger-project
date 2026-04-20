package com.ghostdebugger.ai

import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiIssueMapperTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fileContent = (1..10).joinToString("\n") { "line $it" }

    @Test fun `maps a JsonArray of well-formed issues`() {
        val raw = """[
            {"type":"NULL_SAFETY","severity":"ERROR","title":"x","description":"y","line":3},
            {"type":"HIGH_COMPLEXITY","severity":"WARNING","title":"a","description":"b","line":7}
        ]"""
        val element = json.parseToJsonElement(raw)

        val issues = AiIssueMapper.mapIssues(element, "/foo.ts", fileContent)

        assertEquals(2, issues.size)
        assertEquals(IssueType.NULL_SAFETY, issues[0].type)
        assertEquals(IssueSeverity.ERROR, issues[0].severity)
        assertEquals(3, issues[0].line)
        assertEquals("/foo.ts", issues[0].filePath)
    }

    @Test fun `maps a JsonObject wrapping an issues array`() {
        val raw = """{"issues":[{"type":"NULL_SAFETY","severity":"ERROR","title":"x","description":"y","line":3}]}"""
        val element = json.parseToJsonElement(raw)

        val issues = AiIssueMapper.mapIssues(element, "/foo.ts", fileContent)

        assertEquals(1, issues.size)
    }

    @Test fun `skips items missing type or line`() {
        val raw = """[
            {"type":"NULL_SAFETY","severity":"ERROR","title":"x","description":"y","line":3},
            {"severity":"ERROR","title":"no type","description":"y","line":5},
            {"type":"NULL_SAFETY","severity":"ERROR","title":"no line","description":"y"}
        ]"""
        val element = json.parseToJsonElement(raw)

        val issues = AiIssueMapper.mapIssues(element, "/foo.ts", fileContent)

        assertEquals(1, issues.size)
        assertEquals(3, issues.single().line)
    }

    @Test fun `defaults unknown type to ARCHITECTURE and unknown severity to WARNING`() {
        val raw = """[{"type":"GARBAGE","severity":"BOGUS","title":"x","description":"y","line":2}]"""
        val element = json.parseToJsonElement(raw)

        val issues = AiIssueMapper.mapIssues(element, "/foo.ts", fileContent)

        assertEquals(1, issues.size)
        assertEquals(IssueType.ARCHITECTURE, issues.single().type)
        assertEquals(IssueSeverity.WARNING, issues.single().severity)
    }
}
