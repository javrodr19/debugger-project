package com.ghostdebugger.annotator

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * ExternalAnnotator that surfaces Aegis Debug analysis issues in the
 * editor gutter and the Problems tool window.
 */
class GhostDebuggerAnnotator : ExternalAnnotator<GhostDebuggerAnnotator.FileIssues, List<GhostDebuggerAnnotator.AnnotationInfo>>() {

    data class FileIssues(
        val filePath: String,
        val issues: List<Issue>,
        val document: Document,
        val virtualFile: com.intellij.openapi.vfs.VirtualFile,
        val project: Project
    )

    data class AnnotationInfo(
        val severity: HighlightSeverity,
        val message: String,
        val line: Int,
        val description: String,
        val issue: Issue,
        val fix: com.ghostdebugger.model.CodeFix?
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): FileIssues? {
        return collectInformation(file)
    }

    override fun collectInformation(file: PsiFile): FileIssues? {
        val project = file.project
        val virtualFile = file.virtualFile ?: return null
        val document = file.viewProvider.document ?: return null

        val service = try {
            GhostDebuggerService.getInstance(project)
        } catch (e: Exception) {
            return null
        }

        val filePath = virtualFile.path
        val normalizedPath = filePath.replace("\\", "/")

        // Filter issues belonging to this file
        val fileIssues = service.issuesByFile[normalizedPath] ?: emptyList()

        if (fileIssues.isEmpty()) return null

        return FileIssues(filePath, fileIssues, document, virtualFile, project)
    }

    override fun doAnnotate(collectedInfo: FileIssues?): List<AnnotationInfo>? {
        if (collectedInfo == null) return emptyList()

        val lineCount = collectedInfo.document.lineCount
        val fixDeriver = com.ghostdebugger.fix.FixDeriver(collectedInfo.project)
        val fileContent = collectedInfo.document.text

        return collectedInfo.issues.mapNotNull { issue ->
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            val line = issue.line.coerceIn(1, lineCount)
            val severity = when (issue.severity) {
                IssueSeverity.ERROR -> HighlightSeverity.ERROR
                IssueSeverity.WARNING -> HighlightSeverity.WARNING
                IssueSeverity.INFO -> HighlightSeverity.WEAK_WARNING
            }

            val fix = try {
                fixDeriver.derive(issue, collectedInfo.virtualFile, fileContent)
            } catch (e: Exception) {
                null
            }

            AnnotationInfo(
                severity = severity,
                message = "Aegis Debug: ${issue.title}",
                line = line,
                description = issue.description,
                issue = issue,
                fix = fix
            )
        }
    }

    override fun apply(file: PsiFile, annotations: List<AnnotationInfo>?, holder: AnnotationHolder) {
        if (annotations.isNullOrEmpty()) return

        val document = file.viewProvider.document ?: return
        val lineCount = document.lineCount

        for (info in annotations) {
            val lineIndex = (info.line - 1).coerceIn(0, lineCount - 1)
            val startOffset = document.getLineStartOffset(lineIndex)
            val endOffset = document.getLineEndOffset(lineIndex)

            if (startOffset >= endOffset) continue

            try {
                val range = file.findElementAt(startOffset)?.textRange ?: continue
                val builder = holder.newAnnotation(info.severity, info.message)
                    .range(range)
                    .tooltip("${info.message}\n\n${info.description}")
                    .needsUpdateOnTyping(false)

                if (info.fix != null) {
                    builder.withFix(com.ghostdebugger.fix.AegisQuickFixIntentionAction(info.issue, info.fix))
                }

                builder.create()
            } catch (_: Exception) {
                // Silently skip if annotation creation fails (e.g. bad range)
            }
        }
    }
}