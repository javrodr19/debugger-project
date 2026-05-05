package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * Resolves a deterministic fix for an [Issue] using the registered [Fixer].
 *
 * For Kotlin issues with a project-bound `KtFile`, [Fixer.generateFixFromPsi] is
 * tried first inside a read action. On null, falls back to the line-text-based
 * [Fixer.generateFix].
 *
 * Centralises the "PSI-then-text" decision so [com.ghostdebugger.GhostDebuggerService]
 * doesn't duplicate the logic at every fix call site.
 */
class FixDeriver(
    private val project: Project,
    private val fixerLookup: (Issue) -> Fixer? = FixerRegistry::forIssue
) {
    private val log = logger<FixDeriver>()

    fun derive(issue: Issue, virtualFile: VirtualFile, fileContent: String): CodeFix? {
        val fixer = fixerLookup(issue) ?: return null

        val psiFix = ApplicationManager.getApplication().runReadAction<CodeFix?> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile == null) null else fixer.generateFixFromPsi(issue, psiFile)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("generateFixFromPsi failed for issue ${issue.id}; falling back to text path", e)
                null
            }
        }
        if (psiFix != null) return psiFix

        return fixer.generateFix(issue, fileContent)
    }
}
