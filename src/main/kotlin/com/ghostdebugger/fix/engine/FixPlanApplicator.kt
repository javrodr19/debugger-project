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
 * Applies a [FixPlan] to a file's Document inside a write action and enforces the Tier-1
 * PSI-validity gate: commit the PSI and, if a [PsiErrorElement] appears, revert and reject.
 * [applyVerified] additionally runs the Tier-2 re-analysis gate (see FixVerifier).
 */
class FixPlanApplicator {
    private val log = logger<FixPlanApplicator>()

    /** Result of the Tier-1 apply step. When [ok] is false the document has already been reverted. */
    private data class Tier1Outcome(val ok: Boolean, val original: String)

    /**
     * Runs inside a write action on the EDT. Applies [edits], commits, checks PSI validity. On an
     * error element it reverts the document and returns ok=false; on success it leaves the candidate
     * **committed but unsaved** and returns ok=true with the [Tier1Outcome.original] text for any
     * later revert.
     */
    private fun applyAndCheck(document: Document, edits: List<TextEdit>, project: Project): Tier1Outcome {
        val original = document.text
        for (edit in edits.sortedByDescending { it.startOffset }) {
            document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
        }
        val psiDocMgr = PsiDocumentManager.getInstance(project)
        psiDocMgr.commitDocument(document)

        val psiFile = psiDocMgr.getPsiFile(document)
        val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }
        return if (firstError != null) {
            log.warn("Fix rejected (Tier-1): PSI error after apply: ${firstError.errorDescription}")
            document.setText(original)
            psiDocMgr.commitDocument(document)
            Tier1Outcome(ok = false, original = original)
        } else {
            Tier1Outcome(ok = true, original = original)
        }
    }

    /** Reads the document and computes edits, or returns null with a rejection reason. */
    private fun resolveEdits(plan: FixPlan, virtualFile: VirtualFile, project: Project): Pair<Document, List<TextEdit>>? {
        val fdm = FileDocumentManager.getInstance()
        val document = ApplicationManager.getApplication().runReadAction<Document?> {
            fdm.getDocument(virtualFile)
        } ?: return null
        val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
            val ctx = FixContext(document.text) {
                com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            }
            plan.toEdits(ctx)
        } ?: return null
        return document to edits
    }

    /** Tier-1 only: apply, validity-check, and save on success. Unchanged public behavior. */
    fun apply(plan: FixPlan, virtualFile: VirtualFile, project: Project): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val (document, edits) = resolveEdits(plan, virtualFile, project)
                ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            var succeeded = false
            WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                val outcome = applyAndCheck(document, edits, project)
                if (outcome.ok) {
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
