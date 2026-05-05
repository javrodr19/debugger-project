package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.effectiveType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import java.util.UUID

/**
 * Flags `val x: T = expr` where the initializer's type is not a subtype of T.
 *
 * Only fires when:
 *   - the property has BOTH a declared type and an initializer
 *   - both types resolve (no KaErrorType)
 *   - the initializer's type is not assignable to the declared type
 *
 * Type-mismatch is intentionally analyzer-only — fixes are undecidable without
 * human input (cast? change declared type? change initializer?).
 */
class KotlinTypeMismatchAnalyzer : KotlinAnalyzer() {

    override val name = "KotlinTypeMismatchAnalyzer"
    override val ruleId = "AEG-TYPE-KT-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description =
        "Flags variable declarations whose initializer's type is not assignable to the declared type."

    override fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        for (prop in PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java)) {
            val typeRef = prop.typeReference ?: continue
            val initializer = prop.initializer ?: continue

            with(session) {
                val declared = typeRef.type
                if (isErrorType(declared)) return@with
                val actual = effectiveType(initializer) ?: return@with
                if (isErrorType(actual)) return@with

                if (actual.isSubtypeOf(declared)) return@with
                findings.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.NULL_SAFETY,
                        severity = IssueSeverity.ERROR,
                        title = "Type mismatch on '${prop.name}'",
                        description = "Declared type is not assignable from the initializer's type. Declared: $declared. Initializer: $actual.",
                        filePath = parsedFile.path,
                        line = lineOf(prop.textOffset),
                        codeSnippet = extractSnippet(parsedFile.content, lineOf(prop.textOffset)),
                        affectedNodes = listOf(parsedFile.path),
                        ruleId = ruleId,
                        sources = listOf(IssueSource.STATIC),
                        providers = listOf(EngineProvider.STATIC),
                        confidence = 0.95
                    )
                )
            }
        }
        return findings
    }

    /**
     * `KaErrorType` only catches the canonical error type. K2 also wraps unresolved
     * symbols in `KaClassType` shells that carry an error class reference, which fail
     * the `is KaErrorType` check. The string form `<ERROR ...>` is the stable marker.
     */
    private fun isErrorType(type: KaType): Boolean =
        type is KaErrorType || type.toString().contains("ERROR", ignoreCase = false)

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
