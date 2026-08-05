package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor

open class NavigateFindingAction(private val next: Boolean = true) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val issues = (service.issuesByFile[virtualFile.path] ?: emptyList()).sortedBy { it.line }
        if (issues.isEmpty()) return

        val currentLine = editor.caretModel.logicalPosition.line + 1

        val targetIssue = if (next) {
            issues.firstOrNull { it.line > currentLine } ?: issues.first()
        } else {
            issues.lastOrNull { it.line < currentLine } ?: issues.last()
        }

        val descriptor = OpenFileDescriptor(project, virtualFile, targetIssue.line - 1, targetIssue.column - 1)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null
    }
}

class NextFindingAction : NavigateFindingAction(next = true)
class PrevFindingAction : NavigateFindingAction(next = false)
