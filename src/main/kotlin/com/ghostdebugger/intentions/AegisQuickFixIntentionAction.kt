package com.ghostdebugger.intentions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.fix.FixerRegistry
import com.ghostdebugger.model.Issue
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException

/**
 * Exposes Aegis Debug's deterministic quick-fixes as native editor intentions.
 * When the user places their cursor on a line containing a fixable issue and presses Alt+Enter,
 * this intention action is offered to apply the fix natively with full undo support.
 */
class AegisQuickFixIntentionAction : PsiElementBaseIntentionAction(), IntentionAction {

    // Display-only: title of the issue most recently seen by isAvailable(), used for the menu text.
    // @Volatile (atomic read/write) and NEVER read by invoke() — invoke() recomputes the issue from
    // the live caret, so a stale display title can never cause the wrong fix to apply. The previous
    // `activeIssue` field WAS read by invoke(), which is what made the race a correctness bug. BUG-23.
    @Volatile private var displayTitle: String? = null

    override fun getText(): String =
        displayTitle?.let { "Aegis Debug: Fix '$it'" } ?: "Aegis Debug: Apply Quick-Fix"

    override fun getFamilyName(): String = "Aegis Debug Fixes"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val issue = findFixableIssue(project, editor, element)
        displayTitle = issue?.title
        return issue != null
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return
        val psiFile = element.containingFile ?: return
        val virtualFile = psiFile.virtualFile ?: return

        // Recompute from the current caret rather than reading a shared field. This intention is a
        // singleton reused across editors/carets, and isAvailable() runs frequently on background
        // threads — a mutable activeIssue field raced and could apply the wrong fix or none. BUG-23.
        val issue = findFixableIssue(project, editor, element) ?: return
        val content = psiFile.text
        val result = com.ghostdebugger.fix.engine.FixEngine(project).fix(issue, virtualFile, content)
        if (result is com.ghostdebugger.fix.FixApplyResult.Success) {
            AnalysisOrchestrator.getInstance(project).reanalyzeFile(virtualFile.path)
        }
    }

    /** Stateless lookup of the fixable issue at the current caret, or null. Safe to call from any thread. */
    private fun findFixableIssue(project: Project, editor: Editor?, element: PsiElement): Issue? {
        if (editor == null) return null
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null

        val service = try {
            GhostDebuggerService.getInstance(project)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            return null
        }

        val normalizedPath = virtualFile.path.replace("\\", "/")
        val fileIssues = service.issuesByFile[normalizedPath] ?: return null
        if (fileIssues.isEmpty()) return null

        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset) + 1

        return fileIssues.find { it.line == line && FixerRegistry.forIssue(it) != null }
    }

    override fun startInWriteAction(): Boolean = false
}
