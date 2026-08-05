package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.rules.CustomRuleService
import com.ghostdebugger.rules.RuleMatcher
import com.ghostdebugger.rules.RuleSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

private val log = logger<CustomRuleAnalyzer>()

class CustomRuleAnalyzer : Analyzer {
    override val name = "CustomRuleAnalyzer"
    override val ruleId = "AEG-CUSTOM-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description = "Evaluates repo-specific custom rules declared in .aegis/rules/*.yml."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val rules = CustomRuleService.getInstance(context.project).rules()
        if (rules.isEmpty()) return emptyList()

        val issues = mutableListOf<Issue>()
        val psiManager = PsiManager.getInstance(context.project)
        val psiDocManager = PsiDocumentManager.getInstance(context.project)

        for (parsedFile in context.parsedFiles) {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = psiManager.findFile(parsedFile.virtualFile) ?: return@runReadAction
                val document = psiDocManager.getDocument(psiFile)

                for (rule in rules) {
                    try {
                        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                            override fun visitElement(element: PsiElement) {
                                try {
                                    if (RuleMatcher.matches(element, rule.match)) {
                                        val offset = element.textOffset
                                        val line = document?.getLineNumber(offset)?.plus(1) ?: 1
                                        val col = if (document != null) offset - document.getLineStartOffset(line - 1) + 1 else 1

                                        val severity = when (rule.severity) {
                                            RuleSeverity.ERROR -> IssueSeverity.ERROR
                                            RuleSeverity.WARNING -> IssueSeverity.WARNING
                                            RuleSeverity.WEAK_WARNING -> IssueSeverity.WARNING
                                            RuleSeverity.INFO -> IssueSeverity.INFO
                                        }

                                        issues.add(
                                            Issue(
                                                id = "${rule.id}-$offset",
                                                type = IssueType.CUSTOM_RULE,
                                                severity = severity,
                                                title = "Custom Rule: ${rule.id}",
                                                description = rule.message,
                                                filePath = parsedFile.path,
                                                line = line,
                                                column = col,
                                                codeSnippet = element.text.take(100),
                                                sources = listOf(IssueSource.CUSTOM),
                                                ruleId = rule.id
                                            )
                                        )
                                    }
                                } catch (e: ProcessCanceledException) {
                                    throw e
                                } catch (e: Exception) {
                                    log.debug("Element matching skipped on error", e)
                                }
                                super.visitElement(element)
                            }
                        })
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Rule '${rule.id}' failed on file ${parsedFile.path}", e)
                    }
                }
            }
        }

        return issues
    }
}
