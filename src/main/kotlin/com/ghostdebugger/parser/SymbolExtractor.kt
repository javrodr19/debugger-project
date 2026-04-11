package com.ghostdebugger.parser

import com.ghostdebugger.model.*

class SymbolExtractor {

    fun extract(parsedFile: ParsedFile): ParsedFile {
        return when (parsedFile.extension) {
            "ts", "tsx", "js", "jsx" -> extractFromTypeScript(parsedFile)
            "kt" -> extractFromKotlin(parsedFile)
            "java" -> extractFromJava(parsedFile)
            else -> parsedFile
        }
    }

    private fun extractFromTypeScript(parsedFile: ParsedFile): ParsedFile {
        val content = parsedFile.content
        val lines = content.lines()

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()

        // Regex patterns for TypeScript/JavaScript
        val importRegex = Regex("""^import\s+(?:\{([^}]+)\}|(\w+)|\*\s+as\s+(\w+))\s+from\s+['"]([^'"]+)['"]""")
        val namedImportRegex = Regex("""^import\s+['"]([^'"]+)['"]""")
        val functionRegex = Regex("""(?:export\s+)?(?:async\s+)?(?:function\s+(\w+)|const\s+(\w+)\s*=\s*(?:async\s+)?\(|(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?(?:function|\())""")
        val arrowFunctionRegex = Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)""")
        val constFunctionRegex = Regex("""^(?:export\s+)?const\s+(\w+)\s*(?::\s*\w+)?\s*=\s*(?:async\s+)?\(""")
        val exportRegex = Regex("""^export\s+(?:default\s+)?(?:function|class|const|let|var)\s+(\w+)""")
        val exportDefaultRegex = Regex("""^export\s+default\s+(\w+)""")

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
            exports = exports
        )
    }

    private fun extractFromKotlin(parsedFile: ParsedFile): ParsedFile {
        val content = parsedFile.content
        val lines = content.lines()

        val functions = mutableListOf<FunctionSymbol>()
        val imports = mutableListOf<ImportSymbol>()
        val exports = mutableListOf<ExportSymbol>()

        val importRegex = Regex("""^import\s+([\w.]+)(?:\s+as\s+\w+)?""")
        val funRegex = Regex("""(?:suspend\s+)?fun\s+(\w+)\s*\(""")

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
            if (trimmed.startsWith("class ") || trimmed.startsWith("object ")) {
                val nameMatch = Regex("""(?:class|object)\s+(\w+)""").find(trimmed)
                nameMatch?.let { exports.add(ExportSymbol(name = it.groupValues[1], line = index + 1)) }
            }
        }

        return parsedFile.copy(functions = functions, imports = imports, exports = exports)
    }

    private fun extractFromJava(parsedFile: ParsedFile): ParsedFile {
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
