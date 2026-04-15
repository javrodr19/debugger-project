package com.ghostdebugger.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GhostDebuggerSettingsTest {

    @Test
    fun `default state has provider NONE and safe defaults`() {
        val s = GhostDebuggerSettings.State()
        assertEquals(AIProvider.NONE, s.aiProvider)
        assertEquals("gpt-4o", s.openAiModel)
        assertEquals("http://localhost:11434", s.ollamaEndpoint)
        assertEquals("llama3", s.ollamaModel)
        assertEquals(300, s.maxFilesToAnalyze)
        assertEquals(40, s.maxAiFiles)
        assertEquals(false, s.autoAnalyzeOnOpen)
        assertEquals(true, s.showInfoIssues)
        assertEquals(true, s.cacheEnabled)
        assertEquals(3600L, s.cacheTtlSeconds)
        assertEquals(30_000L, s.aiTimeoutMs)
        assertEquals(false, s.allowCloudUpload)
        assertEquals(false, s.analyzeOnlyChangedFiles)
        assertEquals(256, s.aiCacheMaxEntries)
    }

    @Test
    fun `loadState normalizes invalid values via validate`() {
        val target = GhostDebuggerSettings()
        val bad = GhostDebuggerSettings.State(
            maxFilesToAnalyze = -5,
            maxAiFiles = -1,
            cacheTtlSeconds = -99,
            aiTimeoutMs = 0,
            ollamaEndpoint = "",
            ollamaModel = "",
            openAiModel = "",
            aiCacheMaxEntries = -1
        )
        target.loadState(bad)
        val after = target.state
        assertEquals(300, after.maxFilesToAnalyze)
        assertEquals(0, after.maxAiFiles)
        assertEquals(0L, after.cacheTtlSeconds)
        assertEquals(30_000L, after.aiTimeoutMs)
        assertEquals("http://localhost:11434", after.ollamaEndpoint)
        assertEquals("llama3", after.ollamaModel)
        assertEquals("gpt-4o", after.openAiModel)
        assertEquals(256, after.aiCacheMaxEntries)
    }

    @Test
    fun `snapshot returns detached copy`() {
        val target = GhostDebuggerSettings()
        val snap = target.snapshot()
        target.update { maxFilesToAnalyze = 123 }
        assertNotSame(snap, target.snapshot())
        assertEquals(300, snap.maxFilesToAnalyze)
        assertEquals(123, target.snapshot().maxFilesToAnalyze)
    }

    @Test
    fun `update mutator runs validate on writes`() {
        val target = GhostDebuggerSettings()
        target.update { maxFilesToAnalyze = -99 }
        assertEquals(300, target.snapshot().maxFilesToAnalyze)
    }

    @Test
    fun `legacy setters route through update and validate`() {
        val target = GhostDebuggerSettings()
        target.maxFilesToAnalyze = -1
        assertEquals(300, target.maxFilesToAnalyze)
        target.openAiModel = "gpt-4o-mini"
        assertEquals("gpt-4o-mini", target.openAiModel)
        target.autoAnalyzeOnOpen = true
        assertTrue(target.autoAnalyzeOnOpen)
    }

    @Test
    fun `AIProvider enum contains exactly NONE OPENAI OLLAMA`() {
        assertEquals(listOf("NONE", "OPENAI", "OLLAMA"), AIProvider.values().map { it.name })
    }
}
