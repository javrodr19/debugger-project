package com.ghostdebugger.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AIProvider {
    NONE,
    OPENAI,
    OLLAMA
}

@Service
@State(name = "GhostDebuggerSettings", storages = [Storage("ghostdebugger.xml")])
class GhostDebuggerSettings : PersistentStateComponent<GhostDebuggerSettings.State> {

    data class State(
        var aiProvider: AIProvider = AIProvider.NONE,
        var openAiModel: String = "gpt-4o",
        var ollamaEndpoint: String = "http://localhost:11434",
        var ollamaModel: String = "llama3",
        var maxFilesToAnalyze: Int = 500,
        var maxAiFiles: Int = 100,
        var autoAnalyzeOnOpen: Boolean = false,
        var showInfoIssues: Boolean = true,
        var cacheEnabled: Boolean = true,
        var cacheTtlSeconds: Long = 3600,
        var aiTimeoutMs: Long = 30_000,
        var allowCloudUpload: Boolean = false,
        var analyzeOnlyChangedFiles: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.validate()
    }

    fun snapshot(): State = myState.copy()

    fun update(transform: State.() -> Unit) {
        myState = myState.copy().apply(transform).validate()
    }

    private fun State.validate(): State {
        if (maxFilesToAnalyze <= 0) maxFilesToAnalyze = 500
        if (maxAiFiles < 0) maxAiFiles = 0
        if (cacheTtlSeconds < 0) cacheTtlSeconds = 0
        if (aiTimeoutMs <= 0) aiTimeoutMs = 30_000
        if (ollamaEndpoint.isBlank()) ollamaEndpoint = "http://localhost:11434"
        if (ollamaModel.isBlank()) ollamaModel = "llama3"
        if (openAiModel.isBlank()) openAiModel = "gpt-4o"
        return this
    }

    // Legacy accessors retained for existing call sites (Phase 1 does not rewrite consumers).
    var openAiModel: String
        get() = myState.openAiModel
        set(value) { update { openAiModel = value } }

    var maxFilesToAnalyze: Int
        get() = myState.maxFilesToAnalyze
        set(value) { update { maxFilesToAnalyze = value } }

    var autoAnalyzeOnOpen: Boolean
        get() = myState.autoAnalyzeOnOpen
        set(value) { update { autoAnalyzeOnOpen = value } }

    companion object {
        fun getInstance(): GhostDebuggerSettings =
            ApplicationManager.getApplication().getService(GhostDebuggerSettings::class.java)
    }
}
