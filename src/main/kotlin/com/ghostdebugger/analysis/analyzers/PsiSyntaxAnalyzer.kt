package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.EarlyAnalyzer
import com.ghostdebugger.model.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import java.util.UUID

class PsiSyntaxAnalyzer : EarlyAnalyzer {
    override val name = "PsiSyntaxAnalyzer"
    override val ruleId = "AEG-SYNTAX-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Detects parse-level syntax errors across supported languages using the IDE's PSI tree."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()
        val project = context.project

        for (parsedFile in context.parsedFiles) {
            ProgressManager.checkCanceled()

            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(parsedFile.virtualFile) ?: return@runReadAction
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction
                
                val errors = PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)
                for (element in errors) {
                    ProgressManager.checkCanceled()
                    
                    val offset = element.textRange.startOffset
                    val line = document.getLineNumber(offset) + 1
                    val column = offset - document.getLineStartOffset(line - 1) + 1
                    
                    val errorDescription = element.errorDescription
                    val title = "Syntax error: $errorDescription"
                    val description = "$errorDescription. Aegis Debug will skip additional analysis on this file until the parse error is resolved."
                    
                    val lines = parsedFile.lines
                    val snippet = lines.subList(
                        (line - 3).coerceIn(0, lines.size),
                        (line + 2).coerceAtMost(lines.size)
                    ).joinToString("\n")

                    issues.add(
                        Issue(
                            id = UUID.randomUUID().toString(),
                            type = IssueType.SYNTAX_ERROR,
                            severity = defaultSeverity,
                            title = title,
                            description = description,
                            filePath = parsedFile.path,
                            line = line,
                            column = column,
                            codeSnippet = snippet,
                            affectedNodes = listOf(parsedFile.path),
                            ruleId = ruleId,
                            sources = listOf(IssueSource.STATIC),
                            providers = listOf(EngineProvider.STATIC),
                            confidence = 1.0
                        )
                    )
                }
            }
        }

        return issues
    }
}
