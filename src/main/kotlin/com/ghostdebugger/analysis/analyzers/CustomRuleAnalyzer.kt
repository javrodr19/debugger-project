package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.AnalysisContext
import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.rules.CustomRuleService
import com.ghostdebugger.rules.RuleMatcher
import com.ghostdebugger.rules.RuleSeverity
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

class CustomRuleAnalyzer : Analyzer {

    override fun analyze(context: AnalysisContext): List<Issue> {
        val rules = CustomRuleService.getInstance(context.project).rules()
        if (rules.isEmpty()) return emptyList()

        val file = context.psiFile
        val issues = mutableListOf<Issue>()

        for (rule in rules) {
            try {
                file.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        try {
                            if (RuleMatcher.matches(element, rule.match)) {
                                val document = context.psiFile.viewProvider.document
                                val line = if (document != null) document.getLineNumber(element.textOffset) + 1 else 1
                                val col = if (document != null) element.textOffset - document.getLineStartOffset(line - 1) + 1 else 1

                                val severity = when (rule.severity) {
                                    RuleSeverity.ERROR -> IssueSeverity.ERROR
                                    RuleSeverity.WARNING -> IssueSeverity.WARNING
                                    RuleSeverity.WEAK_WARNING -> IssueSeverity.WARNING
                                    RuleSeverity.INFO -> IssueSeverity.INFO
                                }

                                issues.add(
                                    Issue(
                                        id = "${rule.id}-${element.textOffset}",
                                        type = IssueType.CUSTOM_RULE,
                                        severity = severity,
                                        title = "Custom Rule: ${rule.id}",
                                        description = rule.message,
                                        filePath = context.virtualFile.path,
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
                            // conservative miss
                        }
                        super.visitElement(element)
                    }
                })
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // conservative miss per rule
            }
        }

        return issues
    }
}
