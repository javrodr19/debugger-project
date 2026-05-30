package com.ghostdebugger.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

object EvidenceSubscriptionAdapter {

    private val log = logger<EvidenceSubscriptionAdapter>()

    /**
     * Subscribes a UI surface to evidenceChanged events with uniform EDT marshaling and
     * per-listener exception isolation. Auto-disposed via the parent Disposable.
     */
    fun subscribe(project: Project, parent: Disposable, onAffected: (Set<String>) -> Unit) {
        val store = RuntimeEvidenceStore.getInstance(project)
        store.addListener(object : RuntimeEvidenceStore.EvidenceChangedListener {
            override fun onEvidenceChanged(affected: Set<String>) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    try {
                        onAffected(affected)
                    } catch (e: Exception) {
                        if (e is ProcessCanceledException) throw e
                        log.warn("Isolated subscriber callback failed", e)
                    }
                }
            }
        }, parent)
    }
}
