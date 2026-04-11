package com.ghostdebugger.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(name = "GhostDebuggerSettings", storages = [Storage("ghostdebugger.xml")])
class GhostDebuggerSettings : PersistentStateComponent<GhostDebuggerSettings.State> {

    data class State(
        var openAiModel: String = "gpt-4o",
        var maxFilesToAnalyze: Int = 500,
        var autoAnalyzeOnOpen: Boolean = false,
        var showInfoIssues: Boolean = true,
        var cacheEnabled: Boolean = true,
        var cacheTtlSeconds: Long = 3600
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var openAiModel: String
        get() = state.openAiModel
        set(value) { state.openAiModel = value }

    var maxFilesToAnalyze: Int
        get() = state.maxFilesToAnalyze
        set(value) { state.maxFilesToAnalyze = value }

    var autoAnalyzeOnOpen: Boolean
        get() = state.autoAnalyzeOnOpen
        set(value) { state.autoAnalyzeOnOpen = value }

    companion object {
        fun getInstance(): GhostDebuggerSettings {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                ?.getService(GhostDebuggerSettings::class.java)
                ?: GhostDebuggerSettings()
        }
    }
}
