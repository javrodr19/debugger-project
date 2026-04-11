package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.*

class RustExtractor : LanguageExtractor {
    override fun extract(parsedFile: ParsedFile): ParsedFile {
        val functions = mutableListOf<FunctionSymbol>()
        val fnRegex = Regex("""fn\s+(\w+)\s*(?:<[^>]+>)?\s*\(""")
        parsedFile.content.lines().forEachIndexed { index, line ->
            fnRegex.find(line.trim())?.let { match ->
                functions.add(FunctionSymbol(name = match.groupValues[1], line = index + 1, body = line.trim()))
            }
        }
        return parsedFile.copy(functions = functions)
    }
}
