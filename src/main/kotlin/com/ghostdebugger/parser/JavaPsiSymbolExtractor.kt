package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.openapi.project.Project

/**
 * Stub — replaced with a real PSI extractor in Task 8.
 * Temporarily delegates to the legacy Java regex so the build stays green
 * between this task and Task 8.
 */
class JavaPsiSymbolExtractor(private val project: Project?) {

    fun extract(parsedFile: ParsedFile): ParsedFile = extractWithRegex(parsedFile)

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
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports)
    }

    companion object {
        private val JAVA_IMPORT_REGEX = Regex("^import\\s+([\\w.]+);")
        private val JAVA_METHOD_REGEX = Regex("(?:public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)")
        private val JAVA_RESERVED = setOf("if", "while", "for", "switch")
    }
}
