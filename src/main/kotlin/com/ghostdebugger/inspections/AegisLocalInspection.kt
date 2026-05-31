package com.ghostdebugger.inspections

import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.fix.FixApplicator
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

class AegisLocalQuickFix(
    private val issue: Issue,
    private val fix: CodeFix,
    private val applicator: FixApplicator = FixApplicator()
) : LocalQuickFix {
    override fun getName(): String = "Aegis: Fix '${issue.title}'"
    override fun getFamilyName(): String = "Aegis Debug Quick-Fixes"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val result = applicator.apply(fix, project)
        // Re-run targeted analysis so the corrected issue clears from currentIssues + the NeuroMap,
        // matching the other two fix paths (UIEventRouter, AegisQuickFixIntentionAction). Without
        // this the fixed issue stayed visible until a manual full re-analysis. See BUG-24.
        if (result is FixApplyResult.Success) {
            AnalysisOrchestrator.getInstance(project).reanalyzeFile(issue.filePath)
        }
    }
}

abstract class AegisLocalInspection(val ruleId: String) : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession
    ): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                super.visitFile(file)
                val project = file.project
                val virtualFile = file.virtualFile ?: file.originalFile.virtualFile ?: return
                val document = file.viewProvider.document ?: return
                
                val service = try {
                    GhostDebuggerService.getInstance(project)
                } catch (e: Exception) {
                    return
                }

                val normalizedPath = virtualFile.path.replace("\\", "/")
                val fileIssues = service.issuesByFile[normalizedPath] ?: return
                val matchingIssues = fileIssues.filter { it.ruleId == ruleId }
                if (matchingIssues.isEmpty()) return

                val lineCount = document.lineCount
                val fixDeriver = FixDeriver(project)
                val fileContent = document.text

                for (issue in matchingIssues) {
                    ProgressManager.checkCanceled()
                    val line = issue.line.coerceIn(1, lineCount)
                    val lineIndex = (line - 1).coerceIn(0, lineCount - 1)
                    val startOffset = document.getLineStartOffset(lineIndex)
                    val endOffset = document.getLineEndOffset(lineIndex)
                    if (startOffset >= endOffset) continue

                    var element = file.findElementAt(startOffset)
                    if (element == null) {
                        element = file
                    }
                    
                    val fix = try {
                        fixDeriver.derive(issue, virtualFile, fileContent)
                    } catch (e: Exception) {
                        null
                    }

                    val quickFix = if (fix != null) {
                        AegisLocalQuickFix(issue, fix)
                    } else {
                        null
                    }

                    val message = "Aegis [${issue.ruleId ?: ruleId}]: ${issue.title} - ${issue.description}"

                    try {
                        if (quickFix != null) {
                            holder.registerProblem(element, message, quickFix)
                        } else {
                            holder.registerProblem(element, message)
                        }
                    } catch (e: Exception) {
                        // Silently handle if platform registration fails
                    }
                }
            }
        }
    }
}

class AegisSyntaxInspection : AegisLocalInspection("AEG-SYNTAX-001")
class AegisCompileInspection : AegisLocalInspection("AEG-COMPILE-001")
class AegisNullSafetyInspection : AegisLocalInspection("AEG-NULL-001")
class AegisKotlinNullSafetyInspection : AegisLocalInspection("AEG-NULL-KT-001")
class AegisStateInitInspection : AegisLocalInspection("AEG-STATE-001")
class AegisAsyncFlowInspection : AegisLocalInspection("AEG-ASYNC-001")
class AegisCircularDependencyInspection : AegisLocalInspection("AEG-CYCLE-001")
class AegisComplexityInspection : AegisLocalInspection("AEG-CPX-001")
class AegisKotlinUnsafeCastInspection : AegisLocalInspection("AEG-CAST-KT-001")
class AegisKotlinTypeMismatchInspection : AegisLocalInspection("AEG-TYPE-KT-001")
class AegisKotlinRedundantLetInspection : AegisLocalInspection("AEG-REDUNDANT-LET-KT-001")
