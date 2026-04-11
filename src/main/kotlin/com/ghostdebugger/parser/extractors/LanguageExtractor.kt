package com.ghostdebugger.parser.extractors

import com.ghostdebugger.model.ParsedFile

interface LanguageExtractor {
    fun extract(parsedFile: ParsedFile): ParsedFile
}
