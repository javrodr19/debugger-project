package com.ghostdebugger.store

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException

data class ParsedFrame(
    val fileName: String,
    val line: Int
)

object StackTraceParser {
    private val log = logger<StackTraceParser>()
    private val regex = Regex("""([a-zA-Z0-9_\-\./]+)\.(kt|java|ts|js|tsx|jsx):(\d+)""")

    fun parse(stackTrace: String?): List<ParsedFrame> {
        if (stackTrace.isNullOrBlank()) return emptyList()
        val frames = mutableListOf<ParsedFrame>()
        try {
            val matches = regex.findAll(stackTrace)
            for (match in matches) {
                val path = match.groupValues[1]
                val ext = match.groupValues[2]
                val lineStr = match.groupValues[3]
                val line = lineStr.toIntOrNull()
                if (line != null) {
                    val fullFileName = "$path.$ext"
                    val simpleName = fullFileName.substringAfterLast('/')
                    frames.add(ParsedFrame(simpleName, line))
                }
            }
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            log.warn("Failed to parse stacktrace", e)
        }
        return frames
    }
}
