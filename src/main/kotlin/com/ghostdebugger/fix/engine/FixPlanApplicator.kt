package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Write-safe EDT dispatcher for IntelliJ write actions invoked from coroutines.
 *
 * WHY NOT Dispatchers.Swing:
 * `kotlinx.coroutines.Dispatchers.Swing` dispatches continuations via
 * `SwingUtilities.invokeLater`. IntelliJ's `TransactionGuard` tracks which EDT pumps are
 * write-safe and marks `SwingUtilities.invokeLater` callbacks as write-UNSAFE. Any
 * `WriteCommandAction.runWriteCommandAction(...)` call executed from such a callback throws
 * "Write-unsafe context! Model changes are allowed from write-safe contexts only."
 *
 * WHY THIS WORKS:
 * `ApplicationManager.getApplication().invokeLater(block, ModalityState.defaultModalityState())`
 * is the IntelliJ-native way to schedule work on the EDT. The platform's `TransactionGuard`
 * considers these callbacks write-SAFE, so `WriteCommandAction` runs without error.
 * `ModalityState.defaultModalityState()` (NON_MODAL) is the correct state for background-
 * initiated fix actions; `ModalityState.any()` must NOT be used because it bypasses modality
 * checks entirely and can corrupt state during modal dialogs.
 */
internal val AegisWriteSafeEdt: CoroutineContext = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.defaultModalityState())
    }
}

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

    /**
     * Tier-1 + Tier-2. Applies [plan], runs the PSI-validity gate, then — with the candidate
     * **committed but unsaved** — invokes [reanalyze] off the EDT to re-analyze the live document
     * (the transient-document mechanism: Kotlin analyzers resolve PSI from the virtual file, so the
     * committed candidate is what they see). [verifier] decides; the document is saved on Accept or
     * reverted on Reject. EDT hops use [edtContext] (overridable in tests).
     *
     * Must be called from a coroutine. [reanalyze] must run its own read action and return the
     * issues for this file under the candidate content.
     */
    suspend fun applyVerified(
        plan: FixPlan,
        virtualFile: VirtualFile,
        project: Project,
        target: Issue,
        baselineForFile: List<Issue>,
        reanalyze: suspend () -> List<Issue>,
        verifier: FixVerifier = FixVerifier(),
        edtContext: CoroutineContext = AegisWriteSafeEdt,
    ): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val document = ApplicationManager.getApplication().runReadAction<Document?> {
                fdm.getDocument(virtualFile)
            } ?: return FixApplyResult.Rejected("No document for ${virtualFile.path}")
            val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
                val ctx = FixContext(document.text) {
                    com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                }
                plan.toEdits(ctx)
            } ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            // Tier-1 on the EDT. Reverts itself if the candidate is not PSI-valid.
            var tier1: Tier1Outcome? = null
            withContext(edtContext) {
                WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                    tier1 = applyAndCheck(document, edits, project)
                })
            }
            val outcome = tier1 ?: return FixApplyResult.Failed(IllegalStateException("Tier-1 produced no outcome"))
            if (!outcome.ok) {
                return FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
            }

            // Tier-2 off the EDT: the document now holds the committed (unsaved) candidate.
            val candidateIssues = reanalyze()
            val decision = verifier.decide(target, baselineForFile, candidateIssues)

            // Commit the decision on the EDT: save on Accept, revert on Reject.
            var result: FixApplyResult = FixApplyResult.Failed(IllegalStateException("No decision applied"))
            withContext(edtContext) {
                WriteCommandAction.runWriteCommandAction(project, "Finalize Aegis Debug Fix", null, Runnable {
                    when (decision) {
                        is VerifyDecision.Accept -> {
                            fdm.saveDocument(document)
                            result = FixApplyResult.Success
                        }
                        is VerifyDecision.Reject -> {
                            document.setText(outcome.original)
                            PsiDocumentManager.getInstance(project).commitDocument(document)
                            result = FixApplyResult.Rejected(decision.reason)
                        }
                    }
                })
            }
            result
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
