package com.ghostdebugger.ai

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the cache + dispatch orchestration shared between OllamaService and OpenAIService.
 * Uses a recording subclass that tracks callModel() invocations so we can assert when
 * the cache short-circuits and when it falls through.
 */
class BaseAIServiceTest {

    private class RecordingService(
        cacheEnabled: Boolean,
        private val response: String = "EXPLANATION: ok\n```ts\nfix\n```"
    ) : BaseAIService(
        timeoutMs = 1_000,
        cacheTtlSeconds = 3600,
        cacheEnabled = cacheEnabled,
        cacheMaxEntries = 32
    ) {
        val invocationCount = AtomicInteger(0)
        var lastJsonMode: Boolean = false

        override suspend fun callModel(systemPrompt: String, userPrompt: String, jsonMode: Boolean): String {
            invocationCount.incrementAndGet()
            lastJsonMode = jsonMode
            return response
        }

        override suspend fun callModelStreaming(
            systemPrompt: String,
            userPrompt: String,
            onToken: (String) -> Unit
        ): String {
            invocationCount.incrementAndGet()
            onToken(response)
            return response
        }
    }

    private fun issue(): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "test",
        description = "desc",
        filePath = "/x.ts",
        line = 1,
        codeSnippet = "snippet",
        affectedNodes = emptyList()
    )

    @Test
    fun `explainIssue cache hit short-circuits callModel`() = runBlocking {
        val svc = RecordingService(cacheEnabled = true)
        val i = issue()
        svc.explainIssue(i, "snippet")
        svc.explainIssue(i, "snippet")
        assertEquals(1, svc.invocationCount.get(), "second call should hit cache")
    }

    @Test
    fun `explainIssueStreaming cache hit short-circuits callModel`() = runBlocking {
        val svc = RecordingService(cacheEnabled = true)
        val i = issue()
        var tokensReceived1 = ""
        svc.explainIssueStreaming(i, "snippet") { tokensReceived1 += it }
        
        var tokensReceived2 = ""
        svc.explainIssueStreaming(i, "snippet") { tokensReceived2 += it }
        
        assertEquals(1, svc.invocationCount.get(), "second streaming call should hit cache")
        assertEquals("EXPLANATION: ok\n```ts\nfix\n```", tokensReceived1)
        assertEquals("EXPLANATION: ok\n```ts\nfix\n```", tokensReceived2)
    }

    @Test
    fun `cacheEnabled false skips the cache entirely`() = runBlocking {
        val svc = RecordingService(cacheEnabled = false)
        val i = issue()
        svc.explainIssue(i, "snippet")
        svc.explainIssue(i, "snippet")
        assertEquals(2, svc.invocationCount.get(), "cache disabled — every call hits callModel")
    }

    @Test
    fun `detectIssues passes jsonMode true to callModel`() = runBlocking {
        val svc = RecordingService(cacheEnabled = true, response = "{}")
        svc.detectIssues("/x.ts", "code", emptyList())
        assertTrue(svc.lastJsonMode, "detectIssues must request JSON mode for the model")
    }

    @Test
    fun `detectIssues empty extraction returns empty list`() = runBlocking {
        val svc = RecordingService(cacheEnabled = true, response = "not json at all")
        val issues = svc.detectIssues("/x.ts", "code", emptyList())
        assertTrue(issues.isEmpty())
    }

    @Test
    fun proposeFixPlanDecodesModelJsonIntoAFixPlan() {
        val planJson = """{"issueId":"i9","operations":[{"type":"insertImport","fqName":"a.b.C"}]}"""
        val service = RecordingService(cacheEnabled = false, response = planJson)
        val plan = runBlocking { service.proposeFixPlan(issue(), "some file content", feedback = null) }
        assertNotNull(plan)
        assertEquals("i9", plan!!.issueId)
        assertEquals(1, plan.operations.size)
        assertTrue(plan.operations[0] is com.ghostdebugger.fix.engine.InsertImport)
        assertTrue(service.lastJsonMode, "planning must request JSON mode")
    }

    @Test
    fun proposeFixPlanReturnsNullWhenModelEmitsNoJson() {
        val service = RecordingService(cacheEnabled = false, response = "Sorry, I cannot help.")
        val plan = runBlocking { service.proposeFixPlan(issue(), "x", feedback = null) }
        assertNull(plan)
    }
}
