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
 * The previous body is deliberately gone rather than merely guarded, for three independent reasons:
 *
 * 1. It filtered on `Issue.suggestedFix`, which nothing in this repository ever assigns, so the
 *    loop never executed — the menu item was an unconditional no-op.
 * 2. Reviving it as written would corrupt any file with two or more fixable findings.
 *    `CodeFix.lineStart`/`lineEnd` are plain `Int`s frozen at analysis time, and `toFixPlan`
 *    recomputes character offsets from the *live* document text while still indexing them by those
 *    *stale* line numbers. As soon as one fix changes the file's line count, every later issue's
 *    range addresses shifted lines and silently overwrites unrelated code. (Note the bug is not a
 *    hoisted `document.text` — that getter was re-read each iteration and did reflect prior edits.)
 * 3. It bypassed the fix engine entirely, instantiating `FixPlanApplicator` directly instead of
 *    routing through `FixEngine`'s Tier-1 and Tier-2 gates, and discarded every `FixApplyResult`
 *    inside a bare `runCatching { }`.
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
