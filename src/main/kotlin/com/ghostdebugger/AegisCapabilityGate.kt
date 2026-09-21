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
 *
 * [enabled] itself is never touched by a test — [setEnabledForTest] overrides it for the
 * duration of one test so the code a capability guards can still be exercised, without ever
 * relaxing what actually ships. [resetForTest] restores the shipped state in full.
 */
object AegisCapabilityGate {

    /** Shown verbatim on every gated surface. */
    const val ROADMAP_SENTENCE: String = "This feature will be built further when there's time."

    private const val DIALOG_TITLE: String = "Aegis Debug"

    /** The only decision. Empty in 3.0.0 — every capability ships disabled. */
    private val enabled: Set<AegisCapability> = emptySet()

    /**
     * Test-only override of [enabled]. `null` (the shipped default) means [isEnabled] consults
     * the real, hardcoded set above. Set via [setEnabledForTest] so a test can exercise the
     * post-gate code path of a capability without weakening the gate itself; every test that
     * uses it MUST clear it in `tearDown` via [resetForTest], or the override leaks into
     * unrelated tests — including the release-state assertion in AegisCapabilityGateTest that
     * every capability ships disabled.
     */
    @Volatile
    private var enabledOverrideForTest: Set<AegisCapability>? = null

    private val log = logger<AegisCapabilityGate>()

    private val loggedOnce = Collections.synchronizedSet(mutableSetOf<AegisCapability>())

    private val defaultPresenter: (Project?, AegisCapability) -> Unit = { project, capability ->
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, messageFor(capability), DIALOG_TITLE)
        }
    }

    @Volatile
    private var presenter: (Project?, AegisCapability) -> Unit = defaultPresenter

    fun isEnabled(capability: AegisCapability): Boolean =
        (enabledOverrideForTest ?: enabled).contains(capability)

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

    /**
     * Test-only seam that lifts the gate for exactly the given capabilities, so a test can
     * exercise the code a capability guards without touching the shipped [enabled] set. Must be
     * paired with [resetForTest] in `tearDown`.
     */
    internal fun setEnabledForTest(capabilities: Set<AegisCapability>) {
        enabledOverrideForTest = capabilities
    }

    /**
     * Restores the gate to its shipped state: the real presenter, no enabled-override, and a
     * clear once-log. Call in every test's `tearDown` that calls [setPresenterForTest] or
     * [setEnabledForTest] — an override left set would silently pass or fail unrelated tests,
     * including the release-state assertion that every capability ships disabled.
     */
    internal fun resetForTest() {
        presenter = defaultPresenter
        enabledOverrideForTest = null
        loggedOnce.clear()
    }
}
