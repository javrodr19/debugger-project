package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod

class JavaPsiSymbolExtractor(private val project: Project?) {

    private val log = logger<JavaPsiSymbolExtractor>()

    fun extract(parsedFile: ParsedFile): ParsedFile {
        val psi = readPsi(parsedFile)
        if (psi == null) {
            log.info("Java PSI unavailable for ${parsedFile.path}; using regex fallback")
            return extractWithRegex(parsedFile)
        }
        return runCatching { fromPsi(psi, parsedFile) }
            .onFailure { e ->
                if (e is ProcessCanceledException) throw e
                log.warn("Java PSI extract failed for ${parsedFile.path}, falling back to regex", e)
            }
            .getOrElse { extractWithRegex(parsedFile) }
    }

    private fun readPsi(parsedFile: ParsedFile): PsiJavaFile? {
        val p = project ?: ProjectManager.getInstance().defaultProject
        return runCatching {
            ApplicationManager.getApplication().runReadAction<PsiJavaFile?> {
                val factory = PsiFileFactory.getInstance(p)
                factory.createFileFromText(
                    parsedFile.path.substringAfterLast('/').ifBlank { "Sample.java" },
                    JavaLanguage.INSTANCE,
                    parsedFile.content
                ) as? PsiJavaFile
            }
        }.onFailure { e ->
            if (e is ProcessCanceledException) throw e
            log.warn("Java PSI read failed for ${parsedFile.path}", e)
        }.getOrNull()
    }

    private fun fromPsi(javaFile: PsiJavaFile, parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()

        val document = PsiDocumentManager.getInstance(javaFile.project).getDocument(javaFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        javaFile.importList?.importStatements?.forEach { stmt: PsiImportStatement ->
            val qName = stmt.qualifiedName
            if (!qName.isNullOrBlank()) {
                imports.add(ImportSymbol(source = qName, line = lineOf(stmt.textOffset)))
            }
        }

        val walk = ArrayDeque<PsiElement>()
        walk.addAll(javaFile.classes.toList())
        while (walk.isNotEmpty()) {
            when (val el = walk.removeFirst()) {
                is PsiClass -> {
                    val name = el.name
                    if (!name.isNullOrBlank()) {
                        exports.add(ExportSymbol(name = name, line = lineOf(el.textOffset)))
                    }
                    walk.addAll(el.methods.toList())
                    walk.addAll(el.innerClasses.toList())
                }
                is PsiMethod -> {
                    if (!el.isConstructor) {
                        functions.add(
                            FunctionSymbol(
                                name = el.name,
                                line = lineOf(el.textOffset),
                                body = el.text.take(120)
                            )
                        )
                    }
                }
                else -> {}
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports)
    }

    // ── Regex fallback — exercised when PSI bails on broken input. ──────────

    internal fun extractWithRegex(parsedFile: ParsedFile): ParsedFile {
        val lines = parsedFile.lines
        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            JAVA_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }
            JAVA_METHOD_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name !in JAVA_RESERVED) {
                    functions.add(FunctionSymbol(name = name, line = index + 1, body = trimmed.take(120)))
                }
            }
            JAVA_CLASS_REGEX.find(trimmed)?.let { match ->
                exports.add(ExportSymbol(name = match.groupValues[1], line = index + 1))
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports)
    }

    companion object {
        private val JAVA_IMPORT_REGEX = Regex("^import\\s+([\\w.]+);")
        private val JAVA_METHOD_REGEX = Regex("(?:public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)")
        private val JAVA_CLASS_REGEX = Regex("(?:public\\s+|private\\s+|protected\\s+)?(?:final\\s+|abstract\\s+)?class\\s+(\\w+)")
        private val JAVA_RESERVED = setOf("if", "while", "for", "switch")
    }
}
