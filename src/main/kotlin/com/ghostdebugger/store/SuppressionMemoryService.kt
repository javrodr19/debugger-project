package com.ghostdebugger.store

import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "AegisSuppressionMemory", storages = [Storage("aegis-suppression-memory.xml")])
class SuppressionMemoryService(private val project: Project) :
    PersistentStateComponent<SuppressionMemoryService.State>, Disposable {

    data class State(
        var version: Int = 1,
        var dismissCounts: MutableMap<String, Int> = mutableMapOf()
    )

    private val lock = Any()
    private var dismissCounts = mutableMapOf<String, Int>()

    fun recordDismissal(fingerprint: String) = synchronized(lock) {
        val current = dismissCounts.getOrDefault(fingerprint, 0)
        dismissCounts[fingerprint] = current + 1
    }

    fun shouldAutoHide(fingerprint: String): Boolean = synchronized(lock) {
        val threshold = GhostDebuggerSettings.getInstance().snapshot().suppressionThreshold
        val count = dismissCounts.getOrDefault(fingerprint, 0)
        count >= threshold
    }

    fun reset(fingerprint: String) = synchronized(lock) {
        dismissCounts.remove(fingerprint)
    }

    fun resetAll() = synchronized(lock) {
        dismissCounts.clear()
    }

    fun visibleCount(): Int = synchronized(lock) {
        dismissCounts.size
    }

    override fun getState(): State = synchronized(lock) {
        State(version = 1, dismissCounts = dismissCounts.toMutableMap())
    }

    override fun loadState(state: State) = synchronized(lock) {
        dismissCounts = state.dismissCounts.toMutableMap()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): SuppressionMemoryService =
            project.getService(SuppressionMemoryService::class.java)
    }
}
