package com.ghostdebugger.intentions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.fix.FixApplicator
import com.ghostdebugger.fix.FixDeriver
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

    private var activeIssue: Issue? = null

    override fun getText(): String {
        val issue = activeIssue
        return if (issue != null) {
            "Aegis Debug: Fix '${issue.title}'"
        } else {
            "Aegis Debug: Apply Quick-Fix"
        }
    }

    override fun getFamilyName(): String = "Aegis Debug Fixes"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (editor == null) return false
        val psiFile = element.containingFile ?: return false
        val virtualFile = psiFile.virtualFile ?: return false

        val service = try {
            GhostDebuggerService.getInstance(project)
        } catch (e: Exception) {
            return false
        }

        val normalizedPath = virtualFile.path.replace("\\", "/")
        val fileIssues = service.issuesByFile[normalizedPath] ?: return false
        if (fileIssues.isEmpty()) return false

        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset) + 1

        val issue = fileIssues.find { it.line == line && FixerRegistry.forIssue(it) != null }
        activeIssue = issue
        return issue != null
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return
        val psiFile = element.containingFile ?: return
        val virtualFile = psiFile.virtualFile ?: return

        val issue = activeIssue ?: return
        val content = psiFile.text

        val codeFix = FixDeriver(project).derive(issue, virtualFile, content) ?: return
        val result = FixApplicator().apply(codeFix, project)

        if (result is com.ghostdebugger.fix.FixApplyResult.Success) {
            AnalysisOrchestrator.getInstance(project).reanalyzeFile(virtualFile.path)
        }
    }

    override fun startInWriteAction(): Boolean = false
}
