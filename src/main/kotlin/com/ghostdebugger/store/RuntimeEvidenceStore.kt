package com.ghostdebugger.store

import com.ghostdebugger.model.RuntimeEvidence
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
@State(name = "AegisRuntimeEvidence", storages = [Storage("aegis-runtime-evidence.xml")])
class RuntimeEvidenceStore(private val project: Project) :
    PersistentStateComponent<RuntimeEvidenceStore.State>, Disposable {

    private val log = logger<RuntimeEvidenceStore>()

    data class State(
        var version: Int = 1,
        var evidenceList: MutableList<RuntimeEvidence> = mutableListOf()
    )

    private val lock = Any()
    private val inMemoryMap = mutableMapOf<String, MutableList<RuntimeEvidence>>()
    private val listeners = mutableListOf<EvidenceChangedListener>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    @Volatile private var pendingAffected: Set<String>? = null

    fun record(evidence: RuntimeEvidence) {
        synchronized(lock) {
            val list = inMemoryMap.getOrPut(evidence.fingerprint) { mutableListOf() }
            
            // Limit to 5 entries per source per fingerprint to prevent unbounded growth
            val sameSource = list.filter { it.source == evidence.source }
            if (sameSource.size >= 5) {
                val oldest = sameSource.minByOrNull { it.timestamp }
                if (oldest != null) {
                    list.remove(oldest)
                }
            }
            list.add(evidence)

            val currentPending = pendingAffected ?: emptySet()
            pendingAffected = currentPending + evidence.fingerprint
        }

        // Debounce listener notification off the EDT
        scheduler.schedule({
            val affected = synchronized(lock) {
                val copy = pendingAffected
                pendingAffected = null
                copy
            }
            if (affected != null && affected.isNotEmpty()) {
                fireListeners(affected)
            }
        }, 200, TimeUnit.MILLISECONDS)
    }

    fun lookup(fingerprint: String): List<RuntimeEvidence> = synchronized(lock) {
        inMemoryMap[fingerprint]?.toList() ?: emptyList()
    }

    fun lookupAll(): Map<String, List<RuntimeEvidence>> = synchronized(lock) {
        inMemoryMap.mapValues { it.value.toList() }
    }

    fun clearOrphans(activeFingerprints: Set<String>) = synchronized(lock) {
        val keysToRemove = inMemoryMap.keys.filter { it !in activeFingerprints }
        keysToRemove.forEach { inMemoryMap.remove(it) }
    }

    fun addListener(listener: EvidenceChangedListener, parentDisposable: Disposable) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        // Honor parentDisposable: remove the listener when the subscriber's lifetime ends.
        // Previously the parameter was ignored and listeners were only cleared on store dispose
        // (project close), so transient UI panels/actions leaked their listeners. See BUG-17.
        Disposer.register(parentDisposable, Disposable {
            synchronized(listeners) { listeners.remove(listener) }
        })
    }

    private fun fireListeners(affected: Set<String>) {
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            listenersCopy.forEach {
                try {
                    it.onEvidenceChanged(affected)
                } catch (e: Exception) {
                    if (e is ProcessCanceledException) throw e
                    log.warn("Subscriber threw during onEvidenceChanged", e)
                }
            }
        }
    }

    override fun getState(): State = synchronized(lock) {
        // Growth guard: drop old entries when loading/saving if count is huge (>50,000)
        val allEvidence = inMemoryMap.values.flatten()
        val listToSave = if (allEvidence.size > 50000) {
            log.warn("Evidence store exceeded 50,000 entries. Performing cleanup.")
            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            allEvidence.filter { it.timestamp > thirtyDaysAgo }.toMutableList()
        } else {
            allEvidence.toMutableList()
        }
        State(version = 1, evidenceList = listToSave)
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            if (state.version != 1) {
                log.warn("AegisRuntimeEvidence store schema version mismatch (expected 1, got ${state.version}). Dropping state.")
                inMemoryMap.clear()
                return
            }
            inMemoryMap.clear()
            state.evidenceList.groupByTo(inMemoryMap) { it.fingerprint }
        }
    }

    override fun dispose() {
        synchronized(listeners) {
            listeners.clear()
        }
    }

    fun interface EvidenceChangedListener {
        fun onEvidenceChanged(affected: Set<String>)
    }

    companion object {
        fun getInstance(project: Project): RuntimeEvidenceStore =
            project.getService(RuntimeEvidenceStore::class.java)
    }
}
