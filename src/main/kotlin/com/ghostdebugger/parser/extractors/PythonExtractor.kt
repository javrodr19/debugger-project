package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class PythonExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val defRegex = Regex("""def\s+(\w+)\s*\(""")
        parsedFile.content.lines().forEachIndexed { index, line ->
            defRegex.find(line.trim())?.let { match ->
                functions.add(FunctionSymbol(name = match.groupValues[1], line = index + 1, body = line.trim()))
            }
        }
        return parsedFile.copy(functions = functions)
    }
}
