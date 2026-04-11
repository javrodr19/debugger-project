package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class CSharpExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val methodRegex = Regex("""(?:public|private|protected|static|internal|\s)+[\w<>[\]]+\s+(\w+)\s*\([^)]*\)""")
        val excluded = listOf("if", "while", "for", "switch", "using", "namespace", "class")
        
        parsedFile.content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            methodRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name !in excluded) {
                    functions.add(FunctionSymbol(name = name, line = index + 1, body = trimmed))
                }
            }
        }
        return parsedFile.copy(functions = functions)
    }
}
