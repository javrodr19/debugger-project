package com.ghostdebugger.parser

import com.ghostdebugger.model.*

import com.ghostdebugger.parser.extractors.*

class SymbolExtractor {

    private val extractors: Map<String, LanguageExtractor> = mapOf(
        "ts" to TypeScriptExtractor(),
        "tsx" to TypeScriptExtractor(),
        "js" to TypeScriptExtractor(),
        "jsx" to TypeScriptExtractor(),
        "kt" to KotlinExtractor(),
        "java" to JavaExtractor(),
        "py" to PythonExtractor(),
        "go" to GoExtractor(),
        "rs" to RustExtractor(),
        "cs" to CSharpExtractor(),
        "rb" to RubyExtractor(),
        "swift" to SwiftExtractor(),
        "php" to PhpExtractor()
    )

    fun extract(parsedFile: ParsedFile): ParsedFile {
        return extractors[parsedFile.extension]?.extract(parsedFile) ?: parsedFile
    }
}

