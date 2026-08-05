package com.ghostdebugger.rules

import com.ghostdebugger.parser.KotlinAnalysisHelpers.withKtAnalysis
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

object RuleMatcher {

    fun matches(element: PsiElement, match: RuleMatch): Boolean {
        try {
            if (!matchElementType(element, match.element)) return false

            match.`name-matches`?.let { regexStr ->
                val name = getElementName(element) ?: return false
                if (!Regex(regexStr).containsMatchIn(name)) return false
            }

            match.`text-matches`?.let { regexStr ->
                if (!Regex(regexStr).containsMatchIn(element.text)) return false
            }

            match.`contains-text`?.let { text ->
                if (!element.text.contains(text)) return false
            }

            match.`parameter-type`?.let { expectedType ->
                val paramType = getParameterTypeString(element) ?: return false
                if (paramType == "KaErrorType" || paramType.contains("ERROR")) return false
                if (!paramType.contains(expectedType)) return false
            }

            match.unless?.let { unlessMatch ->
                if (matches(element, unlessMatch)) return false
            }

            return true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return false
        }
    }

    private fun matchElementType(element: PsiElement, elementTypeStr: String): Boolean {
        return when (elementTypeStr.lowercase()) {
            "catch-clause", "catchclause", "ktcatchclause" -> element is KtCatchClause
            "function", "ktnamedfunction" -> element is KtNamedFunction
            "parameter", "ktparameter" -> element is KtParameter
            "property", "ktproperty" -> element is KtProperty
            else -> element.javaClass.simpleName.lowercase().contains(elementTypeStr.lowercase())
        }
    }

    private fun getElementName(element: PsiElement): String? {
        return when (element) {
            is KtNamedFunction -> element.name
            is KtParameter -> element.name
            is KtProperty -> element.name
            else -> null
        }
    }

    private fun getParameterTypeString(element: PsiElement): String? {
        if (element is KtCatchClause) {
            val param = element.catchParameter ?: return null
            return withKtAnalysis(param) {
                val symbol = param.symbol
                val type: KaType = symbol.returnType
                if (type is KaErrorType) null else type.toString()
            }
        }
        return null
    }
}
