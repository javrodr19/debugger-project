package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyReferenceExpression
import java.util.UUID

class PythonStateInitAnalyzer : PythonAnalyzer() {
    override val name = "PythonStateInitAnalyzer"
    override val ruleId = "AEG-STATE-PY-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Flags any read of an instance attribute (self.attr) in the __init__ constructor before it has been assigned."

    override fun analyzePyFile(
        pyFile: PyFile,
        parsedFile: ParsedFile,
        context: AnalysisContext
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(pyFile.project).getDocument(pyFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1
 
        val findings = mutableListOf<Issue>()
        val classes = PsiTreeUtil.findChildrenOfType(pyFile, PyClass::class.java)
        classes.forEach { pyClass ->
            val initMethod = pyClass.statementList.children
                .filterIsInstance<com.jetbrains.python.psi.PyFunction>()
                .find { it.name == "__init__" } ?: return@forEach
            
            val assignedAttrs = mutableSetOf<String>()
            val allElements = mutableListOf<PsiElement>()
            gatherElements(initMethod, allElements)
            
            for (el in allElements) {
                if (el is PyTargetExpression && getQualifierText(el) == "self") {
                    val name = el.name
                    if (name != null) {
                        assignedAttrs.add(name)
                    }
                } else if (el is PyReferenceExpression && getQualifierText(el) == "self") {
                    if (el !is PyTargetExpression) {
                        val name = el.name
                        if (name != null && name !in assignedAttrs) {
                            val line = lineOf(el.textOffset)
                            findings.add(
                                Issue(
                                    id = UUID.randomUUID().toString(),
                                    type = IssueType.STATE_BEFORE_INIT,
                                    severity = IssueSeverity.ERROR,
                                    title = "State read before init: $name",
                                    description = "Variable '$name' read before assignment in constructor.",
                                    filePath = parsedFile.path,
                                    line = line,
                                    codeSnippet = extractSnippet(parsedFile.content, line),
                                    affectedNodes = listOf(parsedFile.path),
                                    ruleId = ruleId,
                                    sources = listOf(IssueSource.STATIC),
                                    providers = listOf(EngineProvider.STATIC),
                                    confidence = 0.95
                                )
                            )
                        }
                    }
                }
            }
        }
 
        return findings
    }

    private fun getQualifierText(el: com.jetbrains.python.psi.PyQualifiedExpression): String? {
        val q = el.qualifier
        if (q != null) return q.text
        return el.children.filterIsInstance<com.jetbrains.python.psi.PyQualifiedExpression>().firstOrNull()?.text
    }

    private fun gatherElements(element: PsiElement, result: MutableList<PsiElement>) {
        for (child in element.children) {
            result.add(child)
            gatherElements(child, result)
        }
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
