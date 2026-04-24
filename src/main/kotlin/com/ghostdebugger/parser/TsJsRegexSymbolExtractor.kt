package com.ghostdebugger.parser

import com.ghostdebugger.model.*

/**
 * Regex-based TS/JS symbol extractor with two pre-passes for robustness:
 *
 *  1. String / comment masking — replaces content inside single quotes,
 *     double quotes, backtick templates, `//` line comments, and
 *     `/* ... */` block comments with spaces of the same length,
 *     preserving line numbers.
 *
 *  2. Multi-line import coalescing — joins lines from an `import {` opener
 *     through the matching `}` into a single logical line for matching,
 *     again preserving the original opener's line number.
 *
 * Masking is used for function / variable / export detection to prevent
 * keywords inside strings or comments from being matched. Import detection
 * is run against the ORIGINAL (unmasked) content so the source path stays
 * intact — a line that begins (after trim) with `import` inside a template
 * literal is uncommon enough that the mask isn't needed as a second gate.
 *
 * IntelliJ Community does not ship JS/TS PSI, so TS/JS stays on regex.
 */
class TsJsRegexSymbolExtractor {

    fun extract(parsedFile: ParsedFile): ParsedFile {
        val maskedLines = maskStringsAndComments(parsedFile.content).lines()
        val originalLines = parsedFile.content.lines()

        val imports = mutableListOf<ImportSymbol>()
        val functions = mutableListOf<FunctionSymbol>()
        val exports = mutableListOf<ExportSymbol>()
        val variables = mutableListOf<VariableSymbol>()

        // Imports use the original content (quote delimiters + source path preserved).
        collapseMultiLineImports(originalLines).forEach { logical ->
            val trimmed = logical.text.trim()
            val lineNo = logical.originalLineNumber

            TS_IMPORT_REGEX.find(trimmed)?.let { match ->
                val namesStr = match.groupValues[1]
                val defaultImport = match.groupValues[2]
                val source = match.groupValues[4]
                val names = when {
                    namesStr.isNotBlank() -> namesStr.split(",")
                        .map { it.trim().split(" as ")[0].trim() }
                        .filter { it.isNotBlank() }
                    defaultImport.isNotBlank() -> listOf(defaultImport)
                    else -> emptyList()
                }
                imports.add(ImportSymbol(source = source, line = lineNo, names = names))
            } ?: TS_NAMED_IMPORT_REGEX.find(trimmed)?.let { match ->
                imports.add(ImportSymbol(source = match.groupValues[1], line = lineNo))
            }
        }

        // Functions / variables / exports use masked content so keywords inside
        // strings or comments cannot trigger matches.
        maskedLines.forEachIndexed { idx, maskedLine ->
            val trimmed = maskedLine.trim()
            val lineNo = idx + 1

            TS_ARROW_FUNCTION_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = lineNo, isAsync = isAsync, body = trimmed.take(120)))
                }
            } ?: TS_CONST_FUNCTION_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    val isAsync = trimmed.contains("async")
                    functions.add(FunctionSymbol(name = name, line = lineNo, isAsync = isAsync, body = trimmed.take(120)))
                }
            } ?: TS_CONST_VAR_REGEX.find(trimmed)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                if (name.isNotBlank() && name != "default") {
                    variables.add(VariableSymbol(name = name, line = lineNo, kind = kind))
                }
            }

            TS_EXPORT_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) exports.add(ExportSymbol(name = name, line = lineNo))
            } ?: TS_EXPORT_DEFAULT_REGEX.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) exports.add(ExportSymbol(name = name, line = lineNo))
            }
        }

        return parsedFile.copy(
            functions = functions, imports = imports, exports = exports, variables = variables
        )
    }

    // ── Pre-passes ──────────────────────────────────────────────────────────

    private data class LogicalLine(val text: String, val originalLineNumber: Int)

    /** Replaces content inside strings/comments with spaces, preserving length and line numbers. */
    internal fun maskStringsAndComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var state = State.CODE

        while (i < src.length) {
            val c = src[i]
            when (state) {
                State.CODE -> when {
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                        out.append("  "); i += 2; state = State.LINE_COMMENT
                    }
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                        out.append("  "); i += 2; state = State.BLOCK_COMMENT
                    }
                    c == '\'' -> { out.append(' '); i++; state = State.SQ_STRING }
                    c == '"'  -> { out.append(' '); i++; state = State.DQ_STRING }
                    c == '`'  -> { out.append(' '); i++; state = State.TEMPLATE }
                    else      -> { out.append(c); i++ }
                }
                State.LINE_COMMENT -> {
                    if (c == '\n') { out.append(c); i++; state = State.CODE }
                    else { out.append(if (c.isWhitespace()) c else ' '); i++ }
                }
                State.BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        out.append("  "); i += 2; state = State.CODE
                    } else {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                State.SQ_STRING -> when {
                    c == '\\' && i + 1 < src.length -> { out.append("  "); i += 2 }
                    c == '\'' -> { out.append(' '); i++; state = State.CODE }
                    c == '\n' -> { out.append(c); i++; state = State.CODE }
                    else -> { out.append(' '); i++ }
                }
                State.DQ_STRING -> when {
                    c == '\\' && i + 1 < src.length -> { out.append("  "); i += 2 }
                    c == '"' -> { out.append(' '); i++; state = State.CODE }
                    c == '\n' -> { out.append(c); i++; state = State.CODE }
                    else -> { out.append(' '); i++ }
                }
                State.TEMPLATE -> when {
                    c == '\\' && i + 1 < src.length -> { out.append("  "); i += 2 }
                    c == '`' -> { out.append(' '); i++; state = State.CODE }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
            }
        }
        return out.toString()
    }

    private enum class State { CODE, LINE_COMMENT, BLOCK_COMMENT, SQ_STRING, DQ_STRING, TEMPLATE }

    /** Collapses `import { a, b, c } from 'x'` across multiple lines into a single logical line. */
    private fun collapseMultiLineImports(lines: List<String>): List<LogicalLine> {
        val out = mutableListOf<LogicalLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val openIdx = line.indexOf('{')
            val looksLikeImport = line.trim().startsWith("import") && openIdx >= 0 && line.indexOf('}') < 0
            if (looksLikeImport) {
                val buf = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !buf.contains('}')) {
                    buf.append(' ').append(lines[j])
                    j++
                }
                out.add(LogicalLine(buf.toString(), i + 1))
                i = j
            } else {
                out.add(LogicalLine(line, i + 1))
                i++
            }
        }
        return out
    }

    companion object {
        private val TS_IMPORT_REGEX = Regex("^import\\s+(?:\\{([^}]+)\\}|(\\w+)|\\*\\s+as\\s+(\\w+))\\s+from\\s+['\"]([^'\"]+)['\"]")
        private val TS_NAMED_IMPORT_REGEX = Regex("^import\\s+['\"]([^'\"]+)['\"]")
        private val TS_ARROW_FUNCTION_REGEX = Regex("^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+(\\w+)")
        private val TS_CONST_FUNCTION_REGEX = Regex("^(?:export\\s+)?const\\s+(\\w+)\\s*(?::\\s*[\\w<>|&\\[\\]]+)?\\s*=\\s*(?:async\\s+)?\\(")
        private val TS_EXPORT_REGEX = Regex("^export\\s+(?:default\\s+)?(?:function|class|const|let|var)\\s+(\\w+)")
        private val TS_EXPORT_DEFAULT_REGEX = Regex("^export\\s+default\\s+(\\w+)")
        private val TS_CONST_VAR_REGEX = Regex("^(?:export\\s+)?(const|let|var)\\s+(\\w+)\\s*(?::\\s*[\\w<>|&\\[\\]\"' ]+)?\\s*=\\s*(?!(?:async\\s+)?\\()")
    }
}
