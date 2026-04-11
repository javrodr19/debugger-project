package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class TypeScriptExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val content = parsedFile.content
        val lines = content.lines()

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        val importRegex = Regex("""^import\s+(?:\{([^}]+)\}|(\w+)|\*\s+as\s+(\w+))\s+from\s+['"]([^'"]+)['"]""")
        val namedImportRegex = Regex("""^import\s+['"]([^'"]+)['"]""")
        val arrowFunctionRegex = Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)""")
        val constFunctionRegex = Regex("""^(?:export\s+)?const\s+(\w+)\s*(?::\s*[\w<>|&\[\]]+)?\s*=\s*(?:async\s+)?\(""")
        val exportRegex = Regex("""^export\s+(?:default\s+)?(?:function|class|const|let|var)\s+(\w+)""")
        val exportDefaultRegex = Regex("""^export\s+default\s+(\w+)""")
        val constVarRegex = Regex("""^(?:export\s+)?(const|let|var)\s+(\w+)\s*(?::\s*[\w<>|&\[\]"' ]+)?\s*=\s*(?!(?:async\s+)?\()""")

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            // Extract imports
            importRegex.find(trimmed)?.let { match ->
                val namesStr = match.groupValues[1]
                val defaultImport = match.groupValues[2]
                val source = match.groupValues[4]
                val names = if (namesStr.isNotBlank()) {
                    namesStr.split(",").map { it.trim().split(" as ")[0].trim() }
                } else if (defaultImport.isNotBlank()) {
                    listOf(defaultImport)
                } else emptyList()
                imports.add(ImportSymbol(source = source, line = index + 1, names = names))
            } ?: namedImportRegex.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }

            // Extract function declarations
            arrowFunctionRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed))
                }
            } ?: constFunctionRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed))
                }
            } ?: constVarRegex.find(trimmed)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank() && name != "default") {
                    variables.add(VariableSymbol(name = name, line = index + 1, kind = kind))
                }
            }

            // Extract exports
            exportRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    exports.add(ExportSymbol(name = name, line = index + 1))
                }
            } ?: exportDefaultRegex.find(trimmed)?.let { match ->
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
}
