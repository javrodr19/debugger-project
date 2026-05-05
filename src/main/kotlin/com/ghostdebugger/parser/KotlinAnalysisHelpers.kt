package com.ghostdebugger.parser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

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
