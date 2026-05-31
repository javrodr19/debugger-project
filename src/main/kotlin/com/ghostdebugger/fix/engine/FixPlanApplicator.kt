package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Applies a [FixPlan] to a file's Document inside a write action, then enforces the Tier-1
 * PSI-validity gate: commit the PSI and, if a [PsiErrorElement] appears, revert and reject. Mirrors
 * FixApplicator.Default's parse-check-and-revert. (For languages without a Community PSI parser,
 * e.g. TS/JS, no error element appears — same behavior as today.)
 */
class FixPlanApplicator {
    private val log = logger<FixPlanApplicator>()

    fun apply(plan: FixPlan, virtualFile: VirtualFile, project: Project): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val document: Document = ApplicationManager.getApplication().runReadAction<Document?> {
                fdm.getDocument(virtualFile)
            } ?: return FixApplyResult.Rejected("No document for ${virtualFile.path}")

            val edits = plan.toEdits(document.text)
                ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            var succeeded = false
            WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                val original = document.text
                for (edit in edits.sortedByDescending { it.startOffset }) {
                    document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
                }
                val psiDocMgr = PsiDocumentManager.getInstance(project)
                psiDocMgr.commitDocument(document)

                val psiFile = psiDocMgr.getPsiFile(document)
                val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }
                if (firstError != null) {
                    log.warn("Fix rejected: PSI error after apply for ${plan.issueId}: ${firstError.errorDescription}")
                    document.setText(original)
                    psiDocMgr.commitDocument(document)
                    succeeded = false
                } else {
                    fdm.saveDocument(document)
                    succeeded = true
                }
            })

            if (succeeded) FixApplyResult.Success
            else FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
