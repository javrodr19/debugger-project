package com.ghostdebugger.actions

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Tools ▸ Aegis Debug ▸ Apply All Deterministic Fixes.
 *
 * Fix application is gated in 3.0.0, so this reports the roadmap notice and does nothing else.
 *
 * The previous body is deliberately gone rather than merely guarded. It filtered on
 * `Issue.suggestedFix`, which nothing in the repository ever assigns, so it was an unconditional
 * no-op; and had it ever been revived it would have corrupted files, because every plan in its
 * loop was derived from `document.text` captured once *before* the first apply shifted the
 * offsets the later plans depended on.
 */
class ApplyAllFixesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        AegisCapabilityGate.blockIfGated(e.project, AegisCapability.FIX_APPLICATION)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null && !virtualFile.isDirectory
    }
}
