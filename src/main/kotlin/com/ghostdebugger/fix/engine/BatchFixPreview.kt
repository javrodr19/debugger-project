package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable

@Serializable
data class BatchPreviewResult(
    val diffs: List<FileFixDiff>,
    val totalFixable: Int,
    val totalSkipped: Int
)

object BatchFixPreview {

    fun previewBatch(
        project: Project,
        items: List<Pair<Issue, FixPlan>>,
        virtualFileMap: Map<String, Pair<VirtualFile, String>>
    ): BatchPreviewResult {
        val diffs = mutableListOf<FileFixDiff>()
        var fixable = 0
        var skipped = 0

        for ((issue, plan) in items) {
            val fileEntry = virtualFileMap[issue.filePath]
            if (fileEntry == null) {
                skipped++
                continue
            }

            val (vFile, originalContent) = fileEntry
            val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
                val ctx = FixContext(originalContent) { PsiManager.getInstance(project).findFile(vFile) }
                plan.toEdits(ctx)
            }

            if (edits == null) {
                skipped++
                continue
            }

            val fixed = StringBuilder(originalContent)
            for (edit in edits.sortedByDescending { it.startOffset }) {
                fixed.replace(edit.startOffset, edit.endOffset, edit.replacement)
            }

            val diff = FixDiffGenerator.generateDiff(
                filePath = issue.filePath,
                issueId = issue.id,
                description = issue.description,
                originalContent = originalContent,
                fixedContent = fixed.toString(),
                isVerified = true
            )
            diffs.add(diff)
            fixable++
        }

        return BatchPreviewResult(diffs, fixable, skipped)
    }
}
