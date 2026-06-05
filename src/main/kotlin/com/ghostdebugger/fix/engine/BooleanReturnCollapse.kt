package com.ghostdebugger.fix.engine

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * Detection + resolution for the boolean-return collapse simplification:
 *   `if (C) return true else return false` -> `return C`
 *   `if (C) return false else return true` -> `return !C`
 *
 * Kotlin is the primary, PSI-verified path (a `KtIfExpression` whose both branches are
 * boolean-literal returns). JS/TS has no Community PSI, so it is a conservative anchored-regex
 * best-effort (a malformed collapse is caught by the engine's Tier-1 / complexity gate). Shared by
 * the [CollapseBooleanReturn] op (one line) and [com.ghostdebugger.fix.ComplexitySimplifierFixer]
 * (whole-file scan), so both agree on what is collapsible.
 */
internal object BooleanReturnCollapse {

    /** A resolved collapse: replace the half-open range [[startOffset], [endOffset]) with [replacement]. */
    data class Collapse(val startOffset: Int, val endOffset: Int, val replacement: String)

    /** Every distinct 1-based line that begins a collapsible boolean-return if/else, in source order. */
    fun sites(ctx: FixContext): List<Int> {
        val ktFile = ctx.psiFile as? KtFile
        return if (ktFile != null) {
            PsiTreeUtil.findChildrenOfType(ktFile, KtIfExpression::class.java)
                .filter { ktCollapse(it) != null }
                .map { LineLocator.lineAt(ctx.content, it.textRange.startOffset) }
                .distinct()
        } else {
            JS_RE.findAll(ctx.content)
                .filter { it.groupValues[2] != it.groupValues[3] }
                .map { LineLocator.lineAt(ctx.content, it.range.first) }
                .distinct()
                .toList()
        }
    }

    /** The collapse anchored at the if/else starting on 1-based [line], or null if none/ambiguous. */
    fun collapseOnLine(ctx: FixContext, line: Int): Collapse? {
        val ktFile = ctx.psiFile as? KtFile
        if (ktFile != null) {
            val ifExpr = PsiTreeUtil.findChildrenOfType(ktFile, KtIfExpression::class.java).firstOrNull {
                LineLocator.lineAt(ctx.content, it.textRange.startOffset) == line && ktCollapse(it) != null
            } ?: return null
            val replacement = ktCollapse(ifExpr) ?: return null
            return Collapse(ifExpr.textRange.startOffset, ifExpr.textRange.endOffset, replacement)
        }
        val m = JS_RE.findAll(ctx.content).firstOrNull {
            LineLocator.lineAt(ctx.content, it.range.first) == line && it.groupValues[2] != it.groupValues[3]
        } ?: return null
        val cond = m.groupValues[1].trim()
        val replacement = if (m.groupValues[2] == "true") "return $cond;" else "return !($cond);"
        return Collapse(m.range.first, m.range.last + 1, replacement)
    }

    // --- Kotlin PSI ---

    /** The collapsed `return …` text for [ifExpr], or null if it is not a boolean-return if/else. */
    private fun ktCollapse(ifExpr: KtIfExpression): String? {
        val thenBool = branchBool(ifExpr.then) ?: return null
        val elseBool = branchBool(ifExpr.`else`) ?: return null
        if (thenBool == elseBool) return null  // `if (C) return true else return true` is not a clean collapse
        val cond = ifExpr.condition ?: return null
        return if (thenBool) "return ${cond.text}" else "return ${negate(cond)}"
    }

    /** true/false if [branch] is (a block wrapping) a `return true` / `return false`; else null. */
    private fun branchBool(branch: KtExpression?): Boolean? {
        val ret = when (branch) {
            is KtReturnExpression -> branch
            is KtBlockExpression -> branch.statements.singleOrNull() as? KtReturnExpression
            else -> null
        } ?: return null
        return when ((ret.returnedExpression as? KtConstantExpression)?.text) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** `!cond` for a trivial condition; `!(cond)` for a compound one (keeps the result parse-clean). */
    private fun negate(cond: KtExpression): String {
        val trivial = cond is KtNameReferenceExpression || cond is KtCallExpression ||
            cond is KtDotQualifiedExpression || cond is KtParenthesizedExpression
        return if (trivial) "!${cond.text}" else "!(${cond.text})"
    }

    // --- JS/TS regex (best-effort) ---
    // group1 = condition, group2 = then-literal, group3 = else-literal. Braces and semicolons optional;
    // DOT_MATCHES_ALL so a block form spanning lines still matches for the condition. Trailing optional
    // brace uses [ \t]* (horizontal whitespace only) to avoid consuming the next line's closing brace.
    // Conservative: declines on anything that does not fit the exact
    // `if (...) return <bool> [;] else return <bool> [;]` shape.
    private val JS_RE = Regex(
        """if\s*\(\s*(.+?)\s*\)\s*\{?\s*return\s+(true|false)\s*;?[ \t]*\}?[ \t]*else[ \t]*\{?\s*return\s+(true|false)\s*;?[ \t]*\}?""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
}
