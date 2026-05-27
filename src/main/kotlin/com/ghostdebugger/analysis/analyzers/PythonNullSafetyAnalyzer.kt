package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyQualifiedExpression
import com.jetbrains.python.psi.PySubscriptionExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.types.TypeEvalContext
import com.jetbrains.python.psi.types.PyUnionType
import java.util.UUID

class PythonNullSafetyAnalyzer : PythonAnalyzer() {
    override val name = "PythonNullSafetyAnalyzer"
    override val ruleId = "AEG-NONE-PY-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Detects attribute access or subscript calls on potentially None/Optional expressions without a guarding check."

    override fun analyzePyFile(
        pyFile: PyFile,
        parsedFile: ParsedFile,
        context: AnalysisContext
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(pyFile.project).getDocument(pyFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        val evalContext = TypeEvalContext.codeAnalysis(pyFile.project, pyFile)

        // 1. Check attribute accesses (e.g., x.attr)
        PsiTreeUtil.findChildrenOfType(pyFile, PyQualifiedExpression::class.java).forEach { el ->
            val receiver = getQualifier(el) ?: return@forEach
            if (isNullable(receiver, evalContext)) {
                val varName = receiver.text
                val line = lineOf(el.textOffset)
                if (!hasNullGuard(varName, line, parsedFile)) {
                    findings.add(buildIssue(parsedFile, varName, line))
                }
            }
        }

        // 2. Check subscript accesses (e.g., x[idx])
        PsiTreeUtil.findChildrenOfType(pyFile, PySubscriptionExpression::class.java).forEach { el ->
            val receiver = el.operand
            if (isNullable(receiver, evalContext)) {
                val varName = receiver.text
                val line = lineOf(el.textOffset)
                if (!hasNullGuard(varName, line, parsedFile)) {
                    findings.add(buildIssue(parsedFile, varName, line))
                }
            }
        }

        return findings
    }

    private fun isNullable(receiver: com.jetbrains.python.psi.PyExpression, evalContext: TypeEvalContext): Boolean {
        val type = evalContext.getType(receiver)
        if (type != null) {
            val isNullableType = (type is PyUnionType && type.members.any { it?.javaClass?.simpleName?.contains("None") == true || it?.name == "None" }) ||
                                 (type.javaClass.simpleName.contains("None")) ||
                                 (type.name == "None")
            if (isNullableType) return true
        }

        if (receiver is PyReferenceExpression) {
            val resolved = findLocalDefinition(receiver)
            if (resolved is com.jetbrains.python.psi.PyNamedParameter) {
                val annotationText = resolved.annotation?.value?.text ?: resolved.annotation?.text
                if (annotationText != null) {
                    if (annotationText.contains("None") || annotationText.contains("Optional")) {
                        return true
                    }
                }
            } else if (resolved is com.jetbrains.python.psi.PyTargetExpression) {
                val assignedValue = resolved.findAssignedValue()
                if (assignedValue != null) {
                    val text = assignedValue.text
                    if (text == "None") return true
                }
            }
        }
        return false
    }

    private fun findLocalDefinition(receiver: PyReferenceExpression): com.intellij.psi.PsiElement? {
        val varName = receiver.referencedName ?: receiver.name ?: receiver.text
        var parent = receiver.parent
        while (parent != null) {
            if (parent is com.jetbrains.python.psi.PyFunction) {
                val parameter = PsiTreeUtil.findChildrenOfType(parent.parameterList, com.jetbrains.python.psi.PyNamedParameter::class.java)
                    .find { (it.name ?: it.text) == varName }
                if (parameter != null) return parameter

                val target = PsiTreeUtil.findChildrenOfType(parent, com.jetbrains.python.psi.PyTargetExpression::class.java)
                    .find { (it.name ?: it.text) == varName }
                if (target != null) return target
            }
            if (parent is PyFile) {
                val target = PsiTreeUtil.findChildrenOfType(parent, com.jetbrains.python.psi.PyTargetExpression::class.java)
                    .find { (it.name ?: it.text) == varName }
                if (target != null) return target
            }
            parent = parent.parent
        }
        return null
    }

    private fun hasNullGuard(varName: String, line: Int, parsedFile: ParsedFile): Boolean {
        val lines = parsedFile.lines
        val index = line - 1
        if (index < 0 || index >= lines.size) return false
        
        // Same line check
        val sameLine = lines[index]
        if (sameLine.contains("if $varName") || sameLine.contains("$varName is not None") ||
            sameLine.contains("if not $varName") || sameLine.contains("$varName and") ||
            sameLine.contains("not $varName") || sameLine.contains("$varName or")) return true
            
        // Previous 5 lines check
        val start = maxOf(0, index - 5)
        for (i in start until index) {
            val prevLine = lines[i]
            if (prevLine.contains("if $varName") || prevLine.contains("$varName is not None") ||
                prevLine.contains("if not $varName") || prevLine.contains("$varName and") ||
                prevLine.contains("not $varName") || prevLine.contains("$varName or")) return true
        }
        return false
    }

    private fun buildIssue(parsedFile: ParsedFile, varName: String, line: Int): Issue {
        return Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Potential None reference: $varName may be None",
            description = "Expression '$varName' may be None/Optional at this access. Guard with an 'if $varName is not None:' check.",
            filePath = parsedFile.path,
            line = line,
            codeSnippet = extractSnippet(parsedFile.content, line),
            affectedNodes = listOf(parsedFile.path),
            ruleId = ruleId,
            sources = listOf(IssueSource.STATIC),
            providers = listOf(EngineProvider.STATIC),
            confidence = 0.95
        )
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }

    private fun getQualifier(el: PyQualifiedExpression): com.jetbrains.python.psi.PyExpression? {
        val q = el.qualifier
        if (q != null) return q
        return el.children.filterIsInstance<com.jetbrains.python.psi.PyExpression>().firstOrNull()
    }
}
