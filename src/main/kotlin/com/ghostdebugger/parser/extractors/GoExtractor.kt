package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class GoExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val funcRegex = Regex("""func\s+(\w+)\s*\(""")
        parsedFile.content.lines().forEachIndexed { index, line ->
            funcRegex.find(line.trim())?.let { match ->
                functions.add(FunctionSymbol(name = match.groupValues[1], line = index + 1, body = line.trim()))
            }
        }
        return parsedFile.copy(functions = functions)
    }
}
