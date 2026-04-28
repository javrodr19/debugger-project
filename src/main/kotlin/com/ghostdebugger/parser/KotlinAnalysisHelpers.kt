package com.ghostdebugger.parser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
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
