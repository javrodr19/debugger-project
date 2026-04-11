package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class KotlinExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val content = parsedFile.content
        val lines = content.lines()

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        val importRegex = Regex("""^import\s+([\w.]+)(?:\s+as\s+\w+)?""")
        val funRegex = Regex("""(?:suspend\s+)?fun\s+(\w+)\s*\(""")
        val valVarRegex = Regex("""^\s*(?:private\s+|protected\s+|public\s+|internal\s+)?(?:override\s+)?(val|var)\s+(\w+)\s*(?::\s*[\w<>?]+)?\s*=""")

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            importRegex.find(trimmed)?.let { match ->
                val source = match.groupValues[1]
                imports.add(ImportSymbol(source = source, line = index + 1))
            }
            funRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                val isAsync = trimmed.contains("suspend")
                functions.add(FunctionSymbol(name = name, line = index + 1, isAsync = isAsync, body = trimmed))
            }
            valVarRegex.find(line)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank()) {
                    variables.add(VariableSymbol(name = name, line = index + 1, kind = kind))
                }
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("object ")) {
                val nameMatch = Regex("""(?:class|object)\s+(\w+)""").find(trimmed)
                nameMatch?.let { exports.add(ExportSymbol(name = it.groupValues[1], line = index + 1)) }
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports, variables = variables)
    }
}
