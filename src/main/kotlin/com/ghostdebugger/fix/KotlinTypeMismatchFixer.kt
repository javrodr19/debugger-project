package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.fix.engine.LineLocator
import com.ghostdebugger.fix.engine.ReplaceRange
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Deterministic fixer for `AEG-TYPE-KT-001` restricted to **lossless numeric widening** (e.g.
 * `Int → Long` via `.toLong()`), the only provably intent-preserving type-mismatch fix. Parses the
 * declared/actual types from the issue description and declines anything that is not a widening pair
 * — broader conversions (`.toString()`, `.toIntOrNull()`, …) guess at intent and are left to the AI
 * planner (gate-verified). PSI-only (no Analysis API), so safe on any call thread.
 *
 * Uses the PSI initializer's exact text range (via `ReplaceRange`) rather than a line-content token
 * search, because `AddExplicitConversion`'s first-occurrence-on-line approach is ambiguous when the
 * initializer identifier also appears in the declared type (e.g. the `n` in `Long`). `ReplaceRange`
 * with PSI-derived offsets is the precise, PSI-valid-by-construction path.
 */
class KotlinTypeMismatchFixer : Fixer {
    override val ruleId = "AEG-TYPE-KT-001"
    override val description = "Applies a lossless numeric widening conversion for an assignable type mismatch; declines non-widening mismatches."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val m = DESC_REGEX.find(issue.description) ?: return null
        val declared = simpleName(m.groupValues[1])
        val actual = simpleName(m.groupValues[2])
        if (declared !in (WIDENING[actual] ?: emptySet())) return null

        val prop = PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java).firstOrNull {
            LineLocator.lineAt(ctx.content, it.textOffset) == issue.line && it.initializer != null
        } ?: return null
        val init = prop.initializer!!
        val initText = init.text
        val conversion = ".to$declared()"
        return FixPlan(issue.id, listOf(ReplaceRange(init.textRange.startOffset, init.textRange.endOffset, "$initText$conversion")))
    }

    private fun simpleName(type: String): String = type.trim().substringAfterLast('.').removeSuffix("?")

    companion object {
        private val DESC_REGEX = Regex("""Declared: (.+?)\. Initializer: (.+)\.""")
        // actual → set of declared types reachable by a lossless widening conversion.
        private val WIDENING: Map<String, Set<String>> = mapOf(
            "Byte" to setOf("Short", "Int", "Long", "Float", "Double"),
            "Short" to setOf("Int", "Long", "Float", "Double"),
            "Char" to setOf("Int", "Long", "Float", "Double"),
            "Int" to setOf("Long", "Float", "Double"),
            "Long" to setOf("Float", "Double"),
            "Float" to setOf("Double"),
        )
    }
}
