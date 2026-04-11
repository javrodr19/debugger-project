package com.ghostdebugger.service

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.bridge.UIEvent
import com.intellij.openapi.diagnostic.logger

class GhostEventHandler(private val service: GhostDebuggerService) {
    private val log = logger<GhostEventHandler>()

    fun handleEvent(event: UIEvent) {
        when (event) {
            is UIEvent.NodeClicked -> service.handleNodeClicked(event.nodeId)
            is UIEvent.NodeDoubleClicked -> service.handleNodeDoubleClicked(event.nodeId)
            is UIEvent.FixRequested -> service.handleFixRequested(event.issueId, event.nodeId)
            is UIEvent.ImpactRequested -> service.handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> service.handleExplainSystem()
            is UIEvent.AnalyzeRequested -> service.analyzeProject()
            is UIEvent.BreakpointSet -> service.handleBreakpointSet(event.filePath, event.line)
            is UIEvent.BreakpointRemoved -> service.handleBreakpointRemoved(event.filePath, event.line)
            is UIEvent.ExportReportRequested -> service.handleExportReportRequested()
            is UIEvent.DebugStepOver -> service.handleDebugAction { it.stepOver(false) }
            is UIEvent.DebugStepInto -> service.handleDebugAction { it.stepInto() }
            is UIEvent.DebugStepOut -> service.handleDebugAction { it.stepOut() }
            is UIEvent.DebugResume -> service.handleDebugAction { it.resume() }
            is UIEvent.DebugPause -> service.handleDebugAction { it.pause() }
            is UIEvent.Unknown -> log.warn("Unknown event: ${event.raw}")
        }
    }
}
