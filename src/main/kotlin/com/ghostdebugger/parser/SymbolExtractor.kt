package com.ghostdebugger.parser

import com.ghostdebugger.model.*

class SymbolExtractor {

    fun extract(parsedFile: ParsedFile): ParsedFile {
        return when (parsedFile.extension) {
            "ts", "tsx", "js", "jsx" -> extractFromTypeScript(parsedFile)
            "kt" -> extractFromKotlin(parsedFile)
            "java" -> extractFromJava(parsedFile)
            else -> parsedFile
        }
    }

    private fun extractFromTypeScript(parsedFile: ParsedFile): ParsedFile {
        val lines = parsedFile.lines

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            // Extract imports
            TS_IMPORT_REGEX.find(trimmed)?.let { match ->
                val namesStr = match.groupValues[1]
                val defaultImport = match.groupValues[2]
                val source = match.groupValues[4]
                val names = if (namesStr.isNotBlank()) {
                    namesStr.split(",").map { it.trim().split(" as ")[0].trim() }
                } else if (defaultImport.isNotBlank()) {
                    listOf(defaultImport)
                } else emptyList()
                imports.add(ImportSymbol(source = source, line = index + 1, names = names))
            } ?: TS_NAMED_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }

            // Extract function declarations
            TS_ARROW_FUNCTION_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed.take(120)))
                }
            } ?: TS_CONST_FUNCTION_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed.take(120)))
                }
            } ?: TS_CONST_VAR_REGEX.find(trimmed)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank() && name != "default") {
                    variables.add(VariableSymbol(name = name, line = index + 1, kind = kind))
                }
            }

            // Extract exports
            TS_EXPORT_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    exports.add(ExportSymbol(name = name, line = index + 1))
                }
            } ?: TS_EXPORT_DEFAULT_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    exports.add(ExportSymbol(name = name, line = index + 1))
                }
            }
        }

        return parsedFile.copy(
            functions = functions,
            imports = imports,
            exports = exports,
            variables = variables
        )
    }

    private fun extractFromKotlin(parsedFile: ParsedFile): ParsedFile {
        val lines = parsedFile.lines

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            KT_IMPORT_REGEX.find(trimmed)?.let { match ->
                val source = match.groupValues[1]
                imports.add(ImportSymbol(source = source, line = index + 1))
            }
            KT_FUN_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                val isAsync = trimmed.contains("suspend")
                functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed.take(120)))
            }
            KT_VAL_VAR_REGEX.find(line)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank()) {
                    variables.add(VariableSymbol(name = name, line = index + 1, kind = kind))
                }
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("object ")) {
                KT_CLASS_OBJECT_REGEX.find(trimmed)?.let { exports.add(ExportSymbol(name = it.groupValues[1], line = index + 1)) }
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports, variables = variables)
    }

    private fun extractFromJava(parsedFile: ParsedFile): ParsedFile {
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
        private val TS_IMPORT_REGEX = Regex("^import\\s+(?:\\{([^}]+)\\}|(\\w+)|\\*\\s+as\\s+(\\w+))\\s+from\\s+['\"]([^'\"]+)['\"]")
        private val TS_NAMED_IMPORT_REGEX = Regex("^import\\s+['\"]([^'\"]+)['\"]")
        private val TS_ARROW_FUNCTION_REGEX = Regex("^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+(\\w+)")
        private val TS_CONST_FUNCTION_REGEX = Regex("^(?:export\\s+)?const\\s+(\\w+)\\s*(?::\\s*[\\w<>|&\\[\\]]+)?\\s*=\\s*(?:async\\s+)?\\(")
        private val TS_EXPORT_REGEX = Regex("^export\\s+(?:default\\s+)?(?:function|class|const|let|var)\\s+(\\w+)")
        private val TS_EXPORT_DEFAULT_REGEX = Regex("^export\\s+default\\s+(\\w+)")
        private val TS_CONST_VAR_REGEX = Regex("^(?:export\\s+)?(const|let|var)\\s+(\\w+)\\s*(?::\\s*[\\w<>|&\\[\\]\"' ]+)?\\s*=\\s*(?!(?:async\\s+)?\\()")

        private val KT_IMPORT_REGEX = Regex("^import\\s+([\\w.]+)(?:\\s+as\\s+\\w+)?")
        private val KT_FUN_REGEX = Regex("(?:suspend\\s+)?fun\\s+(\\w+)\\s*\\(")
        private val KT_VAL_VAR_REGEX = Regex("^\\s*(?:private\\s+|protected\\s+|public\\s+|internal\\s+)?(?:override\\s+)?(val|var)\\s+(\\w+)\\s*(?::\\s*[\\w<>?]+)?\\s*=")
        private val KT_CLASS_OBJECT_REGEX = Regex("(?:class|object)\\s+(\\w+)")

        private val JAVA_IMPORT_REGEX = Regex("^import\\s+([\\w.]+);")
        private val JAVA_METHOD_REGEX = Regex("(?:public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)")
        private val JAVA_RESERVED = setOf("if", "while", "for", "switch")
    }
}
