package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

class KotlinPsiSymbolExtractor(private val project: Project?) {

    private val log = logger<KotlinPsiSymbolExtractor>()

    fun extract(parsedFile: ParsedFile): ParsedFile {
        val psi = readPsi(parsedFile)
        if (psi == null) {
            log.info("Kotlin PSI unavailable for ${parsedFile.path}; using regex fallback")
            return extractWithRegex(parsedFile)
        }
        return runCatching { fromPsi(psi, parsedFile) }
            .onFailure { e ->
                if (e is ProcessCanceledException) throw e
                log.warn("Kotlin PSI extract failed for ${parsedFile.path}, falling back to regex", e)
            }
            .getOrElse { extractWithRegex(parsedFile) }
    }

    private fun readPsi(parsedFile: ParsedFile): KtFile? {
        val p = project ?: ProjectManager.getInstance().defaultProject
        return runCatching {
            ApplicationManager.getApplication().runReadAction<KtFile?> {
                val factory = PsiFileFactory.getInstance(p)
                factory.createFileFromText(
                    parsedFile.path.substringAfterLast('/').ifBlank { "Sample.kt" },
                    KotlinLanguage.INSTANCE,
                    parsedFile.content
                ) as? KtFile
            }
        }.onFailure { e ->
            if (e is ProcessCanceledException) throw e
            log.warn("Kotlin PSI read failed for ${parsedFile.path}", e)
        }.getOrNull()
    }

    private fun fromPsi(ktFile: KtFile, parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        ktFile.importDirectives.forEach { directive: KtImportDirective ->
            val path = directive.importedFqName?.asString()
            if (!path.isNullOrBlank()) {
                imports.add(ImportSymbol(source = path, line = lineOf(directive.textOffset)))
            }
        }

        val walk = ArrayDeque<PsiElement>()
        walk.addAll(ktFile.declarations)
        while (walk.isNotEmpty()) {
            when (val el = walk.removeLast()) {
                is KtNamedFunction -> {
                    val name = el.name
                    if (name != null) {
                        val isAsync = el.hasModifier(KtTokens.SUSPEND_KEYWORD)
                        functions.add(
                            FunctionSymbol(
                                name = name,
                                line = lineOf(el.textOffset),
                                isAsync = isAsync,
                                body = el.text.take(120)
                            )
                        )
                    }
                    walk.addAll(el.children.toList())
                }
                is KtProperty -> {
                    val name = el.name
                    if (name != null) {
                        val kind = if (el.isVar) "var" else "val"
                        variables.add(VariableSymbol(name = name, line = lineOf(el.textOffset), kind = kind))
                    }
                }
                is KtClass -> {
                    val name = el.name
                    if (name != null) {
                        exports.add(ExportSymbol(name = name, line = lineOf(el.textOffset)))
                    }
                    walk.addAll(el.declarations)
                }
                is KtObjectDeclaration -> {
                    val name = el.name
                    if (name != null) {
                        exports.add(ExportSymbol(name = name, line = lineOf(el.textOffset)))
                    }
                    walk.addAll(el.declarations)
                }
                else -> {}
            }
        }

        return parsedFile.copy(
            functions = functions,
            imports = imports,
            exports = exports,
            variables = variables
        )
    }

    // ── Regex fallback — exercised when PSI bails on broken input. ──────────

    internal fun extractWithRegex(parsedFile: ParsedFile): ParsedFile {
        val lines = parsedFile.lines
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            KT_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }
            KT_FUN_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                val isAsync = trimmed.contains("suspend")
                functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed.take(120)))
            }
            KT_VAL_VAR_REGEX.find(line)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank()) variables.add(VariableSymbol(name = name, line = index + 1, kind = kind))
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("object ")) {
                KT_CLASS_OBJECT_REGEX.find(trimmed)?.let {
                    exports.add(ExportSymbol(name = it.groupValues[1], line = index + 1))
                }
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports, variables = variables)
    }

    companion object {
        private val KT_IMPORT_REGEX = Regex("^import\\s+([\\w.]+)(?:\\s+as\\s+\\w+)?")
        private val KT_FUN_REGEX = Regex("(?:suspend\\s+)?fun\\s+(\\w+)\\s*\\(")
        private val KT_VAL_VAR_REGEX = Regex("^\\s*(?:private\\s+|protected\\s+|public\\s+|internal\\s+)?(?:override\\s+)?(val|var)\\s+(\\w+)\\s*(?::\\s*[\\w<>?]+)?\\s*=")
        private val KT_CLASS_OBJECT_REGEX = Regex("(?:class|object)\\s+(\\w+)")
    }
}
