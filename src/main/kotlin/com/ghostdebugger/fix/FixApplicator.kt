package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

fun interface FixWriter {
    fun write(fix: CodeFix, project: Project): Boolean

    companion object {
        val Default = FixWriter { fix, project ->
            val log = logger<FixWriter>()
            try {
                val vf = ApplicationManager.getApplication()
                    .runReadAction<com.intellij.openapi.vfs.VirtualFile?> {
                        LocalFileSystem.getInstance().findFileByPath(fix.filePath)
                    } ?: return@FixWriter false

                val fdm = FileDocumentManager.getInstance()
                val document = ApplicationManager.getApplication()
                    .runReadAction<com.intellij.openapi.editor.Document?> {
                        fdm.getDocument(vf)
                    } ?: return@FixWriter false

                WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                    val startOffset = document.getLineStartOffset(fix.lineStart - 1)
                    val endOffset = document.getLineEndOffset(fix.lineEnd - 1)
                    document.replaceString(startOffset, endOffset, fix.fixedCode)
                    fdm.saveDocument(document)
                })
                true
            } catch (e: Exception) {
                log.warn("FixWriter.Default failed for issue ${fix.issueId}: ${e.message}", e)
                false
            }
        }
    }
}

class FixApplicator(private val writer: FixWriter = FixWriter.Default) {
    fun apply(fix: CodeFix, project: Project): Boolean = writer.write(fix, project)
}
