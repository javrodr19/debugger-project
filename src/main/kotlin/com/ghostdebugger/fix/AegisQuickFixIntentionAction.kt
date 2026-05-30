package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class AegisQuickFixIntentionAction(
    private val issue: Issue,
    private val fix: CodeFix,
    private val applicator: FixApplicator = FixApplicator()
) : IntentionAction {

    override fun getText(): String = "Aegis: Fix '${issue.title}'"

    override fun getFamilyName(): String = "Aegis Debug Quick-Fixes"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        applicator.apply(fix, project)
    }

    override fun startInWriteAction(): Boolean = false
}
