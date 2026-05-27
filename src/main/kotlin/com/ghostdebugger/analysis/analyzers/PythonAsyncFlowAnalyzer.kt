package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyReferenceExpression
import java.util.UUID

class PythonAsyncFlowAnalyzer : PythonAnalyzer() {
    override val name = "PythonAsyncFlowAnalyzer"
    override val ruleId = "AEG-ASYNC-PY-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Flags calls to asynchronous functions (async def) that are not awaited inside another async function."

    override fun analyzePyFile(
        pyFile: PyFile,
        parsedFile: ParsedFile,
        context: AnalysisContext
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(pyFile.project).getDocument(pyFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()

        PsiTreeUtil.findChildrenOfType(pyFile, PyFunction::class.java).forEach { outerFunc ->
            if (!outerFunc.isAsync) return@forEach

            PsiTreeUtil.findChildrenOfType(outerFunc, PyCallExpression::class.java).forEach { call ->
                val calleeRef = call.callee as? PyReferenceExpression ?: return@forEach
                val resolved = resolveCallee(calleeRef) ?: return@forEach
                
                if (resolved.isAsync) {
                    if (!isAwaited(call)) {
                        val line = lineOf(call.textOffset)
                        val name = resolved.name ?: "async function"
                        findings.add(
                            Issue(
                                id = UUID.randomUUID().toString(),
                                type = IssueType.UNHANDLED_PROMISE,
                                severity = IssueSeverity.ERROR,
                                title = "Unawaited call: $name",
                                description = "Asynchronous function '$name' is called without being awaited. Use 'await $name(...)' to wait for execution.",
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

        return findings
    }

    private fun resolveCallee(calleeRef: PyReferenceExpression): PyFunction? {
        val resolved = calleeRef.reference.resolve()
        if (resolved is PyFunction) return resolved

        val varName = calleeRef.referencedName ?: calleeRef.name ?: calleeRef.text
        var parent = calleeRef.parent
        while (parent != null) {
            if (parent is com.jetbrains.python.psi.PyClass) {
                val method = PsiTreeUtil.findChildrenOfType(parent, PyFunction::class.java)
                    .find { (it.name ?: it.text) == varName }
                if (method != null) return method
            }
            if (parent is PyFile) {
                val func = PsiTreeUtil.findChildrenOfType(parent, PyFunction::class.java)
                    .find { (it.name ?: it.text) == varName }
                if (func != null) return func
            }
            parent = parent.parent
        }
        return null
    }

    private fun isAwaited(call: PyCallExpression): Boolean {
        var parent = call.parent
        while (parent != null && parent !is PyFunction) {
            if (parent.javaClass.simpleName == "PyAwaitExpression" || parent.text.startsWith("await ")) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
