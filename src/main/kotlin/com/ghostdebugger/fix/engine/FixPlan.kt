package com.ghostdebugger.fix.engine

import kotlinx.serialization.Serializable

/** An ordered recipe of [FixOperation]s targeting one issue. Serializable for a later AI planner. */
@Serializable
data class FixPlan(val issueId: String, val operations: List<FixOperation>) {
    /** Resolves every operation against [content]; returns null if ANY operation does not apply. */
    fun toEdits(content: String): List<TextEdit>? {
        val edits = ArrayList<TextEdit>(operations.size)
        for (op in operations) edits.add(op.toEdit(content) ?: return null)
        return edits
    }
}
