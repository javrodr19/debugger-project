package com.ghostdebugger.parser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.parents

private val log = logger<KotlinAnalysisHelpersMarker>()

private object KotlinAnalysisHelpersMarker

/**
 * Opens a Kotlin Analysis API session for [file] and runs [block] inside it.
 *
 * Returns null if the session fails to open or the block throws a non-PCE exception.
 * `ProcessCanceledException` is rethrown unconditionally per the V1.2 hardening rule.
 *
 * Callers MUST be inside a read action (Analysis API contract).
 */
internal inline fun <T> withKtAnalysis(
    file: KtFile,
    block: KaSession.(KtFile) -> T
): T? = try {
    analyze(file) { block(file) }
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: Exception) {
    log.warn("Analysis API failure for ${file.virtualFilePath}", e)
    null
}

/**
 * Returns the smart-cast-effective type of [expr], falling back to its declared/inferred
 * type when no smart-cast applies.
 *
 * In K2, `expressionType` returns the declared type at the use site — smart-casts (e.g.,
 * `if (x != null)` or `var x: T?; x = nonNullValue`) are exposed via the separate
 * `smartCastInfo` channel. Analyzers that decide based on type nullability or assignability
 * MUST consult both channels, otherwise they over- or under-flag depending on direction.
 */
internal fun KaSession.effectiveType(expr: KtExpression): KaType? =
    expr.smartCastInfo?.smartCastType ?: expr.expressionType

/**
 * Returns the type of [expr] as narrowed by surrounding control-flow constructs that the
 * Kotlin Analysis API's `smartCastInfo` channel doesn't directly expose at certain AST sites.
 *
 * First tries [effectiveType] (declared type + smartCastInfo). If that's still nullable
 * (or for `is`-narrowing, doesn't match a more specific narrowed type), walks the parent
 * chain looking for:
 *   - Surrounding `KtIfExpression` whose condition narrows [expr]'s name (`x is T` or
 *     `x != null` / `null != x`), with [expr] in the then-branch.
 *   - Preceding sibling `KtBinaryExpression` with `?:` operator whose right side returns
 *     or throws on the same name (Elvis-return idiom).
 *   - Surrounding `KtWhenEntry` with an `is T` condition.
 *
 * Walker stops at function-boundary nodes (KtNamedFunction, KtLambdaExpression,
 * KtClassInitializer) — narrowing doesn't cross them. Invalidates if the variable is
 * reassigned between the guard and the use.
 *
 * Returns null only if `effectiveType(expr)` was null (e.g., file in error state). If the
 * base type is non-nullable already, returns it unchanged.
 */
internal fun KaSession.effectiveTypeWithStructuralSmartCast(
    expr: org.jetbrains.kotlin.psi.KtExpression
): org.jetbrains.kotlin.analysis.api.types.KaType? {
    val baseType = effectiveType(expr) ?: return null
    if (baseType is org.jetbrains.kotlin.analysis.api.types.KaErrorType) {
        // Unknown type — don't attempt structural narrowing.
        return baseType
    }

    val name = (expr as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
        ?: return baseType  // Walker only handles named references.

    // Walk the parent chain looking for narrowing constructs, stopping at function boundaries.
    var cursor: com.intellij.psi.PsiElement? = expr.parent
    while (cursor != null) {
        when (cursor) {
            is org.jetbrains.kotlin.psi.KtNamedFunction,
            is org.jetbrains.kotlin.psi.KtLambdaExpression,
            is org.jetbrains.kotlin.psi.KtClassInitializer -> return baseType  // function boundary

            is org.jetbrains.kotlin.psi.KtIfExpression -> {
                val narrowed = detectIfNarrowing(cursor, name, expr)
                if (narrowed != null && !isReassignedBetween(cursor, expr, name)) {
                    return narrowed
                }
            }

            is org.jetbrains.kotlin.psi.KtWhenEntry -> {
                cursor.conditions.firstNotNullOfOrNull { c ->
                    (c as? org.jetbrains.kotlin.psi.KtWhenConditionIsPattern)
                }?.let { isPattern ->
                    val typeRef = isPattern.typeReference ?: return@let
                    val checkedType = typeRef.type
                    if (checkedType !is org.jetbrains.kotlin.analysis.api.types.KaErrorType) {
                        return checkedType
                    }
                }
            }
        }
        cursor = cursor.parent
    }

    // Elvis-return / Elvis-throw on the same name in any preceding sibling.
    val containingFn = expr.parents.firstOrNull {
        it is org.jetbrains.kotlin.psi.KtNamedFunction ||
        it is org.jetbrains.kotlin.psi.KtLambdaExpression ||
        it is org.jetbrains.kotlin.psi.KtClassInitializer
    }
    if (containingFn != null) {
        for (bin in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            containingFn, org.jetbrains.kotlin.psi.KtBinaryExpression::class.java
        )) {
            if (bin.textOffset >= expr.textOffset) continue
            if (bin.operationToken.toString() != "ELVIS") continue
            val left = (bin.left as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
            val rightText = bin.right?.text?.trim() ?: ""
            if (left == name && (rightText.startsWith("return") || rightText.startsWith("throw"))) {
                return baseType.withNullability(
                    org.jetbrains.kotlin.analysis.api.types.KaTypeNullability.NON_NULLABLE
                )
            }
        }
    }

    return baseType
}

/**
 * Inspects an `if` condition to see whether it narrows [name].
 * Returns the narrowed type if [expr] sits in the then-branch and the condition is a
 * recognised narrowing form; null otherwise.
 */
private fun KaSession.detectIfNarrowing(
    ifExpr: org.jetbrains.kotlin.psi.KtIfExpression,
    name: String,
    expr: org.jetbrains.kotlin.psi.KtExpression
): org.jetbrains.kotlin.analysis.api.types.KaType? {
    val cond = ifExpr.condition ?: return null
    // Only narrow inside the then-branch.
    val thenBranch = ifExpr.then ?: return null
    if (!com.intellij.psi.util.PsiTreeUtil.isAncestor(thenBranch, expr, false)) return null

    // `x is T`
    if (cond is org.jetbrains.kotlin.psi.KtIsExpression) {
        val left = (cond.leftHandSide as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
        if (left == name && !cond.isNegated) {
            val typeRef = cond.typeReference ?: return null
            val t = typeRef.type
            return if (t !is org.jetbrains.kotlin.analysis.api.types.KaErrorType) t else null
        }
    }

    // `x != null` or `null != x`
    if (cond is org.jetbrains.kotlin.psi.KtBinaryExpression && cond.operationToken.toString() == "EXCLEQ") {
        val left = (cond.left as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
        val rightLiteral = cond.right?.text?.trim()
        if (left == name && rightLiteral == "null") {
            return effectiveType(expr)?.withNullability(
                org.jetbrains.kotlin.analysis.api.types.KaTypeNullability.NON_NULLABLE
            )
        }
        val rightSide = (cond.right as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
        val leftLiteral = cond.left?.text?.trim()
        if (rightSide == name && leftLiteral == "null") {
            return effectiveType(expr)?.withNullability(
                org.jetbrains.kotlin.analysis.api.types.KaTypeNullability.NON_NULLABLE
            )
        }
    }

    return null
}

/**
 * Returns true if [name] is reassigned between [guard] and [use] — invalidates the smart-cast.
 */
private fun isReassignedBetween(
    guard: com.intellij.psi.PsiElement,
    use: org.jetbrains.kotlin.psi.KtExpression,
    name: String
): Boolean {
    val container = use.parents.firstOrNull {
        it is org.jetbrains.kotlin.psi.KtNamedFunction ||
        it is org.jetbrains.kotlin.psi.KtLambdaExpression ||
        it is org.jetbrains.kotlin.psi.KtClassInitializer
    } ?: return false
    val guardEnd = guard.textRange.endOffset
    val useStart = use.textOffset
    if (guardEnd >= useStart) return false
    for (bin in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
        container, org.jetbrains.kotlin.psi.KtBinaryExpression::class.java
    )) {
        if (bin.textOffset < guardEnd || bin.textOffset > useStart) continue
        if (bin.operationToken.toString() != "EQ") continue
        val left = (bin.left as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
        if (left == name) return true
    }
    return false
}
