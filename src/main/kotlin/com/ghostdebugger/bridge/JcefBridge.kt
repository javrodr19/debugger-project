package com.ghostdebugger.bridge

import com.ghostdebugger.model.*
import com.ghostdebugger.model.DebugVariable
import com.ghostdebugger.model.EngineStatusPayload
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class JcefBridge(
    private val browser: JBCefBrowser,
    private val onEvent: (UIEvent) -> Unit
) : Disposable {
    private val log = logger<JcefBridge>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var query: JBCefJSQuery? = null

    fun initialize() {
        query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query!!.addHandler { message ->
            try {
                val event = UIEventParser.parse(message)
                log.info("JcefBridge received event: ${event::class.simpleName}")
                onEvent(event)
            } catch (e: Exception) {
                log.error("Failed to parse UI event: $message", e)
            }
            JBCefJSQuery.Response("ok")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridgeScript()
                }
            }
        }, browser.cefBrowser)
    }

    private fun injectBridgeScript() {
        val q = query ?: return
        val injectScript = """
            (function() {
                window.__jcefQuery__ = function(msg) {
                    ${q.inject("msg")}
                };
                if (window.__aegis_debug__ && window.__aegis_debug__.__ready__) {
                    window.__aegis_debug__.__ready__();
                }
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(injectScript, browser.cefBrowser.url ?: "about:blank", 0)
    }

    fun sendGraphData(graph: ProjectGraph) {
        val graphJson = json.encodeToString(graph)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onGraphUpdate($graphJson)")
    }

    fun sendIssuesForFile(filePath: String, issues: List<Issue>) {
        val payload = json.encodeToString(mapOf("filePath" to filePath, "issues" to issues))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onIssuesForFile($payload)")
    }

    fun sendIssueExplanation(issueId: String, explanation: String) {
        val explanationEscaped = json.encodeToString(mapOf("issueId" to issueId, "explanation" to explanation))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onExplanation($explanationEscaped)")
    }

    fun sendFixSuggestion(fix: CodeFix) {
        val fixJson = json.encodeToString(fix)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onFixSuggestion($fixJson)")
    }

    fun sendFixApplied(issueId: String) {
        val payload = json.encodeToString(mapOf("issueId" to issueId))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onFixApplied($payload)")
    }

    fun sendNodeUpdate(nodeId: String, status: NodeStatus) {
        val escapedId = nodeId.replace("\"", "\\\"")
        val payload = """{"nodeId":"$escapedId","status":"${status.name}"}"""
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onNodeUpdate($payload)")
    }

    fun sendAnalysisStart() {
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisStart()")
    }

    fun sendAnalysisComplete(errorCount: Int, warningCount: Int, healthScore: Double) {
        val payload = """{"errorCount":$errorCount,"warningCount":$warningCount,"healthScore":$healthScore}"""
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisComplete($payload)")
    }

    fun sendAnalysisProgress(text: String, fraction: Double) {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val payload = """{"text":"$escaped","fraction":$fraction}"""
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisProgress($payload)")
    }

    fun sendError(message: String) {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onError(\"$escaped\")")
    }

    fun sendSystemExplanation(explanation: String) {
        val escaped = explanation.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onSystemExplanation(\"$escaped\")")
    }

    fun sendImpactAnalysis(nodeId: String, affectedNodes: List<String>) {
        val affectedJson = json.encodeToString(affectedNodes)
        val escapedId = nodeId.replace("\"", "\\\"")
        val payload = """{"nodeId":"$escapedId","affectedNodes":$affectedJson}"""
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onImpactAnalysis($payload)")
    }

    fun sendDebugFrame(nodeId: String, filePath: String, line: Int, variables: List<DebugVariable>) {
        val varsJson = json.encodeToString(variables)
        val escapedId = nodeId.replace("\"", "\\\"")
        val escapedPath = filePath.replace("\\", "/").replace("\"", "\\\"")
        val payload = """{"nodeId":"$escapedId","filePath":"$escapedPath","line":$line,"variables":$varsJson}"""
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugFrame($payload)")
    }

    fun sendDebugSessionEnded() {
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugSessionEnded()")
    }

    fun sendDebugStateChanged(state: String) {
        val escaped = state.replace("\"", "\\\"")
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugStateChanged(\"$escaped\")")
    }

    fun sendAutoRefreshStart() {
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAutoRefreshStart()")
    }

    fun sendEngineStatus(payload: EngineStatusPayload) {
        val payloadJson = json.encodeToString(payload)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onEngineStatus($payloadJson)")
    }

    private fun executeJS(script: String) {
        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "about:blank", 0)
        } catch (e: Exception) {
            log.warn("Failed to execute JavaScript", e)
        }
    }

    override fun dispose() {
        query?.dispose()
        query = null
    }
}
