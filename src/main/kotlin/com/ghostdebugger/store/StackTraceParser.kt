package com.ghostdebugger.store

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException

data class ParsedFrame(
    val fileName: String,
    val line: Int
)

object StackTraceParser {
    private val log = logger<StackTraceParser>()
    
    // Highly robust regex patterns represent:
    // 1. Parenthesized traces (e.g. Jest, Vitest, Node ESM, Webpack): at foo (webpack:///./src/Foo.ts:125:7)
    // 2. Bare prefixed traces (e.g. Node, Webpack bare): at /home/user/src/Foo.ts:125:7
    // 3. Raw path traces (e.g. Mocha, Karma): test/index.spec.js:15:10
    private val patterns = listOf(
        Regex("""\((?:file:///)?(?:webpack:///)?([^()]+?)\.(kt|java|ts|js|tsx|jsx):(\d+)(?::\d+)?\)"""),
        Regex("""\bat\s+(?:file:///)?(?:webpack:///)?([^\s()]+?)\.(kt|java|ts|js|tsx|jsx):(\d+)(?::\d+)?\b"""),
        Regex("""\b([^\s()]+?)\.(kt|java|ts|js|tsx|jsx):(\d+)(?::\d+)?\b""")
    )

    fun parse(stackTrace: String?): List<ParsedFrame> {
        if (stackTrace.isNullOrBlank()) return emptyList()
        val frames = mutableListOf<ParsedFrame>()
        try {
            for (pattern in patterns) {
                val matches = pattern.findAll(stackTrace)
                for (match in matches) {
                    val path = match.groupValues[1]
                    val ext = match.groupValues[2]
                    val lineStr = match.groupValues[3]
                    val line = lineStr.toIntOrNull()
                    if (line != null) {
                        val fullFileName = "$path.$ext"
                        // Strip both separators. Windows traces (e.g. C:\proj\src\File.ts) contain
                        // no '/', so substringAfterLast('/') alone left the full backslash path as
                        // the "filename", which never matched slash-normalized issue paths in the
                        // TestRunObserver cross-checker. See BUG-18.
                        val simpleName = fullFileName.substringAfterLast('/').substringAfterLast('\\')
                        frames.add(ParsedFrame(simpleName, line))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            log.warn("Failed to parse stacktrace", e)
        }
        return frames.distinctBy { Pair(it.fileName, it.line) }
    }
}
