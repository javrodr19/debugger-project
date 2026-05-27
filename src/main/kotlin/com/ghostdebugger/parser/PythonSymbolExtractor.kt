package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyImportStatement
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyTargetExpression

class PythonSymbolExtractor(private val project: Project?) {

    private val log = logger<PythonSymbolExtractor>()

    fun extract(parsedFile: ParsedFile): ParsedFile {
        val psi = readPsi(parsedFile)
        if (psi == null) {
            log.info("Python PSI unavailable for ${parsedFile.path}; using regex fallback")
            return extractWithRegex(parsedFile)
        }
        return runCatching { fromPsi(psi, parsedFile) }
            .onFailure { e ->
                if (e is ProcessCanceledException) throw e
                log.warn("Python PSI extract failed for ${parsedFile.path}, falling back to regex", e)
            }
            .getOrElse { extractWithRegex(parsedFile) }
    }

    private fun readPsi(parsedFile: ParsedFile): PyFile? {
        val app = ApplicationManager.getApplication() ?: return null
        val p = project ?: runCatching { ProjectManager.getInstance().defaultProject }.getOrNull() ?: return null
        return runCatching {
            app.runReadAction<PyFile?> {
                PsiManager.getInstance(p).findFile(parsedFile.virtualFile) as? PyFile
            }
        }.onFailure { e ->
            if (e is ProcessCanceledException) throw e
            log.warn("Python PSI read failed for ${parsedFile.path}", e)
        }.getOrNull()
    }

    private fun fromPsi(pyFile: PyFile, parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        val document = PsiDocumentManager.getInstance(pyFile.project).getDocument(pyFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        PsiTreeUtil.findChildrenOfType(pyFile, PyImportStatement::class.java).forEach { stmt ->
            stmt.importElements.forEach { element ->
                val name = element.importedQName?.toString()
                if (!name.isNullOrBlank()) {
                    imports.add(ImportSymbol(source = name, line = lineOf(stmt.textOffset)))
                }
            }
        }

        PsiTreeUtil.findChildrenOfType(pyFile, PyFromImportStatement::class.java).forEach { stmt ->
            val source = stmt.importSourceQName?.toString()
            if (!source.isNullOrBlank()) {
                imports.add(ImportSymbol(source = source, line = lineOf(stmt.textOffset)))
            }
        }

        PsiTreeUtil.findChildrenOfType(pyFile, PyFunction::class.java).forEach { el ->
            val name = el.name
            if (name != null) {
                val isAsync = el.isAsync
                functions.add(
                    FunctionSymbol(
                        name = name,
                        line = lineOf(el.textOffset),
                        isAsync = isAsync,
                        body = boundedBody(document, el)
                    )
                )
            }
        }

        PsiTreeUtil.findChildrenOfType(pyFile, PyTargetExpression::class.java).forEach { el ->
            val name = el.name
            if (name != null && el.qualifier == null) {
                variables.add(VariableSymbol(name = name, line = lineOf(el.textOffset), kind = "var"))
            }
        }

        PsiTreeUtil.findChildrenOfType(pyFile, PyClass::class.java).forEach { el ->
            val name = el.name
            if (name != null) {
                exports.add(ExportSymbol(name = name, line = lineOf(el.textOffset)))
            }
        }

        return parsedFile.copy(
            functions = functions,
            imports = imports,
            exports = exports,
            variables = variables
        )
    }

    private fun boundedBody(document: com.intellij.openapi.editor.Document?, el: PsiElement): String {
        val limit = 120
        if (document == null) return el.text.take(limit)
        val start = el.textOffset
        if (start >= document.textLength) return ""
        val end = minOf(start + limit, el.textRange.endOffset, document.textLength)
        return document.getText(com.intellij.openapi.util.TextRange(start, end))
    }

    internal fun extractWithRegex(parsedFile: ParsedFile): ParsedFile {
        val lines = parsedFile.lines
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            PY_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }
            PY_FROM_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }
            
            PY_FUN_REGEX.find(trimmed)?.let { match ->
                val isAsync = trimmed.startsWith("async ")
                val name = match.groupValues[1]
                functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed.take(120)))
            }
            
            PY_VAR_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                variables.add(VariableSymbol(name = name, line = index + 1, kind = "var"))
            }
            
            PY_CLASS_REGEX.find(trimmed)?.let { match ->
                exports.add(ExportSymbol(name = match.groupValues[1], line = index + 1))
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports, variables = variables)
    }

    companion object {
        private val PY_IMPORT_REGEX = Regex("""^import\s+([\w.,\s]+)""")
        private val PY_FROM_IMPORT_REGEX = Regex("""^from\s+([\w.]+)\s+import""")
        private val PY_FUN_REGEX = Regex("""(?:async\s+)?def\s+(\w+)\s*\(""")
        private val PY_VAR_REGEX = Regex("""^\s*(\w+)\s*=\s*[^=]""")
        private val PY_CLASS_REGEX = Regex("""^class\s+(\w+)\s*(?:\(|:)""")
    }
}
