package com.ghostdebugger.ai.prompts

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PromptTemplatesFewShotTest {

    @Test fun `detectIssues prompt contains each few-shot example verbatim`() {
        val rendered = PromptTemplates.detectIssues("/foo.ts", "const a = null;\na.b;")
        assertTrue(
            rendered.contains(PromptExamples.EXAMPLE_NULL_SAFETY_BLOCK),
            "detectIssues prompt should contain the null-safety example"
        )
        assertTrue(
            rendered.contains(PromptExamples.EXAMPLE_CIRCULAR_DEP_BLOCK),
            "detectIssues prompt should contain the circular-dep example"
        )
        assertTrue(
            rendered.contains(PromptExamples.EXAMPLE_CLEAN_FILE_BLOCK),
            "detectIssues prompt should contain the clean-file example"
        )
    }

    @Test fun `detectIssues prompt still references the target file path`() {
        val rendered = PromptTemplates.detectIssues("/foo.ts", "x")
        assertTrue(rendered.contains("/foo.ts"))
    }

    @Test fun `jointFix prompt contains each few-shot example verbatim`() {
        val issue = Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "t", description = "d",
            filePath = "/a.ts", line = 1, codeSnippet = "", affectedNodes = listOf("/a.ts")
        )
        val rendered = PromptTemplates.jointFix(
            issue,
            brokenFiles = mapOf("/a.ts" to "const x = 1;"),
            healthyContext = emptyMap()
        )
        assertTrue(
            rendered.contains(PromptExamples.EXAMPLE_JOINT_FIX_SINGLE_FILE),
            "jointFix prompt should contain the single-file example"
        )
        assertTrue(
            rendered.contains(PromptExamples.EXAMPLE_JOINT_FIX_TWO_FILES),
            "jointFix prompt should contain the two-file example"
        )
    }

    @Test fun `few-shot example bodies render valid JSON`() {
        // If we ever break an example by hand-editing and introduce malformed JSON,
        // this catches it — each example body must round-trip through the parser.
        val json = kotlinx.serialization.json.Json
        listOf(
            PromptExamples.EXAMPLE_NULL_SAFETY_OUTPUT_JSON,
            PromptExamples.EXAMPLE_CIRCULAR_DEP_OUTPUT_JSON,
            PromptExamples.EXAMPLE_CLEAN_FILE_OUTPUT_JSON,
            PromptExamples.EXAMPLE_JOINT_FIX_SINGLE_FILE_OUTPUT_JSON,
            PromptExamples.EXAMPLE_JOINT_FIX_TWO_FILES_OUTPUT_JSON
        ).forEach { body ->
            assertDoesNotThrow({ json.parseToJsonElement(body) }, "Invalid JSON in example: $body")
        }
    }
}
