package com.ghostdebugger.fix.engine

/**
 * Pure line/offset targeting for [FixOperation]s. Lines are 1-based; offsets are 0-based char
 * indices into the file content. Robust to line shifts because targets are resolved at apply-time.
 */
object LineLocator {
    /** Half-open char range [first, last] (inclusive) of 1-based [line], excluding the trailing newline; null if out of range. */
    fun lineRange(content: String, line: Int): IntRange? {
        if (line < 1) return null
        var start = 0
        var current = 1
        while (current < line) {
            val nl = content.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1
            current++
        }
        if (start > content.length) return null
        val nl = content.indexOf('\n', start)
        val endExclusive = if (nl < 0) content.length else nl
        return start until endExclusive  // empty range if blank line
    }

    /** Absolute offset of the first occurrence of [token] within 1-based [line], or null. */
    fun indexOfOn(content: String, line: Int, token: String): Int? {
        val range = lineRange(content, line) ?: return null
        val lineText = content.substring(range.first, (range.last + 1).coerceAtMost(content.length))
        val idx = lineText.indexOf(token)
        return if (idx < 0) null else range.first + idx
    }

    /** 1-based line number containing absolute [offset]. */
    fun lineAt(content: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, content.length)
        return content.substring(0, clamped).count { it == '\n' } + 1
    }
}
