package com.ghostdebugger.bridge

import com.ghostdebugger.model.*
import com.ghostdebugger.model.DebugVariable
import com.ghostdebugger.model.EngineStatusPayload
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

import com.ghostdebugger.store.RuntimeEvidenceStore
import com.ghostdebugger.store.SuppressionMemoryService
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.model.ConfidenceCalculator
import com.ghostdebugger.model.Confidence
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

class JcefBridge(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val onEvent: (UIEvent) -> Unit
) : Disposable, BridgeChannel {
    private val log = logger<JcefBridge>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var query: JBCefJSQuery? = null
    private var loadHandler: CefLoadHandlerAdapter? = null

    fun initialize() {
        // Idempotent: a second call would orphan the first JBCefJSQuery (leaked, never disposed)
        // and register a duplicate load handler that injects the bridge script twice per page
        // load. NeuroMapPanel + GhostDebuggerService.setBridge both used to call this. See BUG-01.
        if (query != null) return

        query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query!!.addHandler { message ->
            try {
                val event = UIEventParser.parse(message)
                log.info("JcefBridge received event: ${event::class.simpleName}")
                onEvent(event)
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                log.error("Failed to parse UI event: $message", e)
            }
            JBCefJSQuery.Response("ok")
        }

        val handler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridgeScript()
                }
            }
        }
        loadHandler = handler
        browser.jbCefClient.addLoadHandler(handler, browser.cefBrowser)
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

    private fun augmentIssues(issues: List<Issue>): List<JsonElement> {
        val store = RuntimeEvidenceStore.getInstance(project)
        val suppression = SuppressionMemoryService.getInstance(project)

        return issues.map { issue ->
            val fingerprint = issue.fingerprint()
            val evidence = store.lookup(fingerprint)
            val confidence = ConfidenceCalculator.calculate(evidence)
            val suppressed = suppression.shouldAutoHide(fingerprint)

            val issueJson = json.encodeToJsonElement(Issue.serializer(), issue).jsonObject
            buildJsonObject {
                issueJson.forEach { key, value -> put(key, value) }
                put("dynamicConfidence", confidence.name)
                put("suppressed", suppressed)
            }
        }
    }

    fun sendGraphData(graph: ProjectGraph) {
        val augmentedNodes = graph.nodes.map { node ->
            val augmentedIssues = augmentIssues(node.issues)
            val nodeJson = json.encodeToJsonElement(GraphNode.serializer(), node).jsonObject
            buildJsonObject {
                nodeJson.forEach { key, value ->
                    if (key == "issues") {
                        put("issues", JsonArray(augmentedIssues))
                    } else {
                        put(key, value)
                    }
                }
            }
        }
        val graphJson = json.encodeToJsonElement(ProjectGraph.serializer(), graph).jsonObject
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val augmentedGraph = buildJsonObject {
            graphJson.forEach { key, value ->
                if (key == "nodes") {
                    put("nodes", JsonArray(augmentedNodes))
                } else {
                    put(key, value)
                }
            }
            put("showSuppressed", settings.showSuppressed)
            put("showUnreached", settings.showUnreached)
        }
        val graphStr = json.encodeToString(augmentedGraph)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onGraphUpdate($graphStr)")
    }

    override fun sendIssuesForFile(filePath: String, issues: List<Issue>) {
        val augmented = augmentIssues(issues)
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val payload = buildJsonObject {
            put("filePath", filePath)
            put("issues", JsonArray(augmented))
            put("showSuppressed", settings.showSuppressed)
            put("showUnreached", settings.showUnreached)
        }
        val payloadStr = json.encodeToString(payload)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onIssuesForFile($payloadStr)")
    }

    fun sendIssueExplanation(issueId: String, explanation: String) {
        val explanationEscaped = json.encodeToString(mapOf("issueId" to issueId, "explanation" to explanation))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onExplanation($explanationEscaped)")
    }

    fun sendIssueExplanationChunk(issueId: String, chunk: String, isComplete: Boolean) {
        // isComplete MUST be a JSON boolean, not the string "true"/"false": the TS receiver does a
        // strict `payload.isComplete === true` check. buildJsonObject.put(String, Boolean) emits a
        // real boolean (the old mapOf(... to isComplete.toString()) emitted "true"). See BUG-12.
        val payload = buildJsonObject {
            put("issueId", issueId)
            put("chunk", chunk)
            put("isComplete", isComplete)
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onExplanationChunk($payload)")
    }

    fun sendFixSuggestion(fix: CodeFix) {
        val fixJson = json.encodeToString(fix)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onFixSuggestion($fixJson)")
    }

    fun sendFixApplied(issueId: String) {
        val payload = json.encodeToString(mapOf("issueId" to issueId))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onFixApplied($payload)")
    }

    override fun sendNodeUpdate(nodeId: String, status: NodeStatus) {
        val payload = json.encodeToString(mapOf("nodeId" to nodeId, "status" to status.name))
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onNodeUpdate($payload)")
    }

    fun sendAnalysisStart() {
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisStart()")
    }

    fun sendAnalysisComplete(errorCount: Int, warningCount: Int, healthScore: Double) {
        // Heterogeneous numeric map — kotlinx.serialization's reified encoder can't infer
        // a serializer for Map<String, Number>. buildJsonObject is the explicit-typed path.
        val payload = buildJsonObject {
            put("errorCount", errorCount)
            put("warningCount", warningCount)
            put("healthScore", healthScore)
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisComplete($payload)")
    }

    fun sendAnalysisProgress(text: String, fraction: Double) {
        val payload = buildJsonObject {
            put("text", text)
            put("fraction", fraction)
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisProgress($payload)")
    }

    fun sendError(message: String) {
        // Raw string on the wire (JS receiver expects message: string). encodeToString
        // produces a properly-escaped JSON string literal — backslashes, newlines,
        // tabs, control chars are all handled by the encoder.
        val escaped = json.encodeToString(message)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onError($escaped)")
    }

    fun sendSystemExplanation(explanation: String) {
        val escaped = json.encodeToString(explanation)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onSystemExplanation($escaped)")
    }

    fun sendSystemExplanationChunk(chunk: String, isComplete: Boolean) {
        // Same boolean-on-the-wire fix as sendIssueExplanationChunk. See BUG-12.
        val payload = buildJsonObject {
            put("chunk", chunk)
            put("isComplete", isComplete)
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onSystemExplanationChunk($payload)")
    }

    fun sendImpactAnalysis(nodeId: String, affectedNodes: List<String>) {
        val payload = buildJsonObject {
            put("nodeId", nodeId)
            put("affectedNodes", buildJsonArray { affectedNodes.forEach { add(it) } })
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onImpactAnalysis($payload)")
    }

    fun sendDebugFrame(nodeId: String, filePath: String, line: Int, variables: List<DebugVariable>) {
        val payload = buildJsonObject {
            put("nodeId", nodeId)
            put("filePath", filePath.replace("\\", "/"))
            put("line", line)
            put("variables", json.encodeToJsonElement(ListSerializer(DebugVariable.serializer()), variables))
        }
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugFrame($payload)")
    }

    fun sendDebugSessionEnded() {
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugSessionEnded()")
    }

    fun sendDebugStateChanged(state: String) {
        val escaped = json.encodeToString(state)
        executeJS("window.__aegis_debug__ && window.__aegis_debug__.onDebugStateChanged($escaped)")
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
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.warn("Failed to execute JavaScript", e)
        }
    }

    override fun dispose() {
        // Remove the load handler explicitly. Disposal is LIFO under the shared parentDisposable:
        // the bridge is registered after the browser, so it disposes first and the client is still
        // alive here. See BUG-11. (The browser-owned client would also release it on its own
        // disposal, so this is deterministic cleanup rather than a permanent-leak fix.)
        loadHandler?.let { browser.jbCefClient.removeLoadHandler(it, browser.cefBrowser) }
        loadHandler = null
        query?.dispose()
        query = null
    }
}
