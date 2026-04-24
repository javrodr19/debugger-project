package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.openapi.project.Project

/**
 * Stub — replaced with a real PSI extractor in Task 7.
 * Temporarily delegates to the legacy Kotlin regex so the build stays green
 * between this task and Task 7.
 */
class KotlinPsiSymbolExtractor(private val project: Project?) {

    fun extract(parsedFile: ParsedFile): ParsedFile = extractWithRegex(parsedFile)

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
