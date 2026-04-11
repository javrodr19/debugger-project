package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class JavaExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val content = parsedFile.content
        val lines = content.lines()

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()

        val importRegex = Regex("""^import\s+([\w.]+);""")
        val methodRegex = Regex("""(?:public|private|protected|static|\s)+[\w<>[\]]+\s+(\w+)\s*\([^)]*\)""")

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            importRegex.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = index + 1))
            }
            methodRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name !in listOf("if", "while", "for", "switch")) {
                    functions.add(FunctionSymbol(name = name, line = index + 1, body = trimmed))
                }
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports)
    }
}
