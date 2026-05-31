package com.ghostdebugger.fix.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A deterministic, PSI-valid-by-construction semantic transformation. Each operation converts to a
 * [TextEdit] against the file content, or returns null if it does not apply (offset out of range,
 * pattern absent) — never an invalid edit. Serializable so a later AI planner can emit a plan as
 * JSON. Phase 1 ships only [ReplaceRange]; the catalog grows later.
 */
@Serializable
sealed class FixOperation {
    abstract fun toEdit(content: String): TextEdit?
}

/** Replace the half-open range [startOffset, endOffset) with [text]. */
@Serializable
@SerialName("replaceRange")
data class ReplaceRange(val startOffset: Int, val endOffset: Int, val text: String) : FixOperation() {
    override fun toEdit(content: String): TextEdit? {
        if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) return null
        return TextEdit(startOffset, endOffset, text)
    }
}
