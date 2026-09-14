package com.ghostdebugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.Collections

/**
 * The single place that decides whether a gated capability is available.
 *
 * Two entry points exist because the contexts differ. [blockIfGated] is for surfaces the user
 * just clicked, and raises a dialog. [skipIfGated] is for background work — an analysis pass, a
 * listener callback — where a modal dialog would itself be a defect; it is silent and logs once.
 *
 * Lifting the gate on a capability means adding it to [enabled]. Nothing else changes.
 */
object AegisCapabilityGate {

    /** Shown verbatim on every gated surface. */
    const val ROADMAP_SENTENCE: String = "This feature will be built further when there's time."

    private const val DIALOG_TITLE: String = "Aegis Debug"

    /** The only decision. Empty in 3.0.0 — every capability ships disabled. */
    private val enabled: Set<AegisCapability> = emptySet()

    private val log = logger<AegisCapabilityGate>()

    private val loggedOnce = Collections.synchronizedSet(mutableSetOf<AegisCapability>())

    private val defaultPresenter: (Project?, AegisCapability) -> Unit = { project, capability ->
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, messageFor(capability), DIALOG_TITLE)
        }
    }

    @Volatile
    private var presenter: (Project?, AegisCapability) -> Unit = defaultPresenter

    fun isEnabled(capability: AegisCapability): Boolean = capability in enabled

    /**
     * Guard for user-initiated surfaces. Presents the roadmap dialog and returns true when the
     * caller must abort. Returns false — and shows nothing — once the capability is enabled.
     */
    fun blockIfGated(project: Project?, capability: AegisCapability): Boolean {
        if (isEnabled(capability)) return false
        presenter(project, capability)
        return true
    }

    /**
     * Guard for background surfaces. Never shows UI. Returns true when the caller must skip.
     * Logs at most once per capability so a per-file analysis pass cannot flood the log.
     */
    fun skipIfGated(capability: AegisCapability): Boolean {
        if (isEnabled(capability)) return false
        if (loggedOnce.add(capability)) {
            log.info("Skipping gated capability ${capability.name}: ${capability.why}")
        }
        return true
    }

    /** Built with explicit concatenation, not a trimIndent template — see CLAUDE.md. */
    fun messageFor(capability: AegisCapability): String =
        capability.label + "\n\n" + ROADMAP_SENTENCE + "\n\n" + capability.why

    internal fun setPresenterForTest(p: (Project?, AegisCapability) -> Unit) {
        presenter = p
    }

    internal fun resetPresenterForTest() {
        presenter = defaultPresenter
        loggedOnce.clear()
    }
}
