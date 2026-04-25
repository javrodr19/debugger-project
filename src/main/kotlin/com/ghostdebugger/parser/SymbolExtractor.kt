package com.ghostdebugger.parser

import com.ghostdebugger.model.*
import com.intellij.openapi.project.Project

/**
 * Language-dispatching symbol extractor. Kotlin and Java go through
 * PSI-backed extractors (added in V1.2); TS/JS uses the regex-based
 * [TsJsRegexSymbolExtractor] because IntelliJ Community does not bundle
 * JS/TS PSI.
 *
 * When constructed with a null [project], PSI paths fall back to their
 * regex predecessors (retained as private helpers on each PSI extractor) —
 * useful for pure-JUnit tests that have no fixture.
 */
class SymbolExtractor(private val project: Project? = null) {

    private val tsJs = TsJsRegexSymbolExtractor()
    private val kotlinPsi by lazy { KotlinPsiSymbolExtractor(project) }
    private val javaPsi by lazy { JavaPsiSymbolExtractor(project) }

    fun extract(parsedFile: ParsedFile): ParsedFile = when (parsedFile.extension) {
        "ts", "tsx", "js", "jsx" -> tsJs.extract(parsedFile)
        "kt" -> kotlinPsi.extract(parsedFile)
        "java" -> javaPsi.extract(parsedFile)
        else -> parsedFile
    }
}
