package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.UUID

/**
 * Renders a [FixPlan] as a before/after [CodeFix] for the webview fix-suggestion preview.
 * Computes the plan's edits against [content] (read-only PSI access for PSI-based operations such
 * as ConvertToSafeCast) and applies them to a text copy. Returns null when the plan does not apply
 * (stale offsets, pattern absent). The preview is advisory; applying the fix re-derives and verifies
 * through the Tier-2 gate, so a previewed plan and the eventually-applied plan may differ.
 */
object FixPlanPreview {
    fun render(
        plan: FixPlan,
        project: Project,
        virtualFile: VirtualFile,
        content: String,
        issue: Issue,
    ): CodeFix? {
        val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
            val ctx = FixContext(content) { PsiManager.getInstance(project).findFile(virtualFile) }
            plan.toEdits(ctx)
        } ?: return null

        val fixed = StringBuilder(content)
        for (edit in edits.sortedByDescending { it.startOffset }) {
            fixed.replace(edit.startOffset, edit.endOffset, edit.replacement)
        }

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "AI-proposed fix (verified when applied)",
            originalCode = content,
            fixedCode = fixed.toString(),
            filePath = issue.filePath,
            lineStart = 1,
            lineEnd = content.lines().size,
            isDeterministic = false,
            confidence = 0.7,
        )
    }
}
