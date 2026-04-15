package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

sealed class FixApplyResult {
    data object Success : FixApplyResult()
    data class Rejected(val reason: String) : FixApplyResult()
    data class Failed(val throwable: Throwable) : FixApplyResult()
}

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

                var succeeded = false
                WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                    val startOffset = document.getLineStartOffset(fix.lineStart - 1)
                    val endOffset = document.getLineEndOffset(fix.lineEnd - 1)
                    val originalText = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))

                    document.replaceString(startOffset, endOffset, fix.fixedCode)

                    val psiDocMgr = PsiDocumentManager.getInstance(project)
                    psiDocMgr.commitDocument(document)

                    val psiFile = psiDocMgr.getPsiFile(document)
                    val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }

                    if (firstError != null) {
                        log.warn(
                            "Fix rejected: PSI error after apply for issue ${fix.issueId} " +
                            "at offset ${firstError.textOffset}: ${firstError.errorDescription}"
                        )
                        val newEnd = startOffset + fix.fixedCode.length
                        document.replaceString(startOffset, newEnd, originalText)
                        psiDocMgr.commitDocument(document)
                        succeeded = false
                    } else {
                        fdm.saveDocument(document)
                        succeeded = true
                    }
                })
                succeeded
            } catch (e: Exception) {
                log.warn("FixWriter.Default failed for issue ${fix.issueId}: ${e.message}", e)
                false
            }
        }
    }
}

class FixApplicator(private val writer: FixWriter = FixWriter.Default) {
    fun apply(fix: CodeFix, project: Project): FixApplyResult {
        return try {
            if (writer.write(fix, project)) FixApplyResult.Success
            else FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
