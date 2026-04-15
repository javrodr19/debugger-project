import type { ProjectGraph, CodeFix, AnalysisMetrics, ImpactAnalysis, NodeStatus, DebugFrame, EngineStatusPayload, AnalysisProgressPayload, Issue } from '../types'
import type { DebugSessionState } from '../stores/appStore'

type EventHandler<T> = (data: T) => void

interface AegisAPI {
  onGraphUpdate: (data: ProjectGraph) => void
  onIssuesForFile: (data: { filePath: string; issues: Issue[] }) => void
  onExplanation: (data: { issueId: string; explanation: string }) => void
  onFixSuggestion: (fix: CodeFix) => void
  onNodeUpdate: (data: { nodeId: string; status: NodeStatus }) => void
  onAnalysisStart: () => void
  onAnalysisProgress: (data: AnalysisProgressPayload) => void
  onAnalysisComplete: (metrics: AnalysisMetrics) => void
  onError: (message: string) => void
  onSystemExplanation: (explanation: string) => void
  onImpactAnalysis: (data: ImpactAnalysis) => void
  onDebugFrame: (data: DebugFrame) => void
  onDebugSessionEnded: () => void
  onDebugStateChanged: (state: string) => void
  onAutoRefreshStart: () => void
  onEngineStatus: (data: EngineStatusPayload) => void
  onFixApplied:   (data: { issueId: string }) => void
  __ready__?: () => void
}

declare global {
  interface Window {
    __aegis_debug__: AegisAPI
    __jcefQuery__: (msg: string) => void
  }
}

class PluginBridge {
  private graphHandlers: EventHandler<ProjectGraph>[] = []
  private issuesForFileHandlers: EventHandler<{ filePath: string; issues: Issue[] }>[] = []
  private explanationHandlers: EventHandler<{ issueId: string; explanation: string }>[] = []
  private fixHandlers: EventHandler<CodeFix>[] = []
  private nodeUpdateHandlers: EventHandler<{ nodeId: string; status: NodeStatus }>[] = []
  private analysisStartHandlers: EventHandler<void>[] = []
  private analysisProgressHandlers: EventHandler<AnalysisProgressPayload>[] = []
  private analysisCompleteHandlers: EventHandler<AnalysisMetrics>[] = []
  private errorHandlers: EventHandler<string>[] = []
  private systemExplanationHandlers: EventHandler<string>[] = []
  private impactHandlers: EventHandler<ImpactAnalysis>[] = []
  private debugFrameHandlers: EventHandler<DebugFrame>[] = []
  private debugSessionEndedHandlers: EventHandler<void>[] = []
  private debugStateChangedHandlers: EventHandler<DebugSessionState>[] = []
  private autoRefreshStartHandlers: EventHandler<void>[] = []
  private engineStatusHandlers: EventHandler<EngineStatusPayload>[] = []
  private fixAppliedHandlers:   EventHandler<{ issueId: string }>[] = []

  constructor() {
    this.setupAPI()
  }

  private setupAPI() {
    window.__aegis_debug__ = {
      onGraphUpdate: (data) => this.graphHandlers.forEach(h => h(data)),
      onIssuesForFile: (data) => this.issuesForFileHandlers.forEach(h => h(data)),
      onExplanation: (data) => this.explanationHandlers.forEach(h => h(data)),
      onFixSuggestion: (fix) => this.fixHandlers.forEach(h => h(fix)),
      onNodeUpdate: (data) => this.nodeUpdateHandlers.forEach(h => h(data)),
      onAnalysisStart: () => this.analysisStartHandlers.forEach(h => h()),
      onAnalysisProgress: (data) => this.analysisProgressHandlers.forEach(h => h(data)),
      onAnalysisComplete: (metrics) => this.analysisCompleteHandlers.forEach(h => h(metrics)),
      onError: (msg) => this.errorHandlers.forEach(h => h(msg)),
      onSystemExplanation: (exp) => this.systemExplanationHandlers.forEach(h => h(exp)),
      onImpactAnalysis: (data) => this.impactHandlers.forEach(h => h(data)),
      onDebugFrame: (data) => this.debugFrameHandlers.forEach(h => h(data)),
      onDebugSessionEnded: () => this.debugSessionEndedHandlers.forEach(h => h()),
      onDebugStateChanged: (state) => this.debugStateChangedHandlers.forEach(h => h(state as DebugSessionState)),
      onAutoRefreshStart: () => this.autoRefreshStartHandlers.forEach(h => h()),
      onEngineStatus: (data) => this.engineStatusHandlers.forEach(h => h(data)),
      onFixApplied:   (data) => this.fixAppliedHandlers.forEach(h => h(data)),
      __ready__: () => {},
    }
  }

  // Event subscriptions returning cleanup functions
  onGraphUpdate(handler: EventHandler<ProjectGraph>) {
    this.graphHandlers.push(handler)
    return () => { this.graphHandlers = this.graphHandlers.filter(h => h !== handler) }
  }
  onIssuesForFile(handler: EventHandler<{ filePath: string; issues: Issue[] }>) {
    this.issuesForFileHandlers.push(handler)
    return () => { this.issuesForFileHandlers = this.issuesForFileHandlers.filter(h => h !== handler) }
  }
  onExplanation(handler: EventHandler<{ issueId: string; explanation: string }>) {
    this.explanationHandlers.push(handler)
    return () => { this.explanationHandlers = this.explanationHandlers.filter(h => h !== handler) }
  }
  onFixSuggestion(handler: EventHandler<CodeFix>) {
    this.fixHandlers.push(handler)
    return () => { this.fixHandlers = this.fixHandlers.filter(h => h !== handler) }
  }
  onNodeUpdate(handler: EventHandler<{ nodeId: string; status: NodeStatus }>) {
    this.nodeUpdateHandlers.push(handler)
    return () => { this.nodeUpdateHandlers = this.nodeUpdateHandlers.filter(h => h !== handler) }
  }
  onAnalysisStart(handler: EventHandler<void>) {
    this.analysisStartHandlers.push(handler)
    return () => { this.analysisStartHandlers = this.analysisStartHandlers.filter(h => h !== handler) }
  }
  onAnalysisProgress(handler: EventHandler<AnalysisProgressPayload>) {
    this.analysisProgressHandlers.push(handler)
    return () => { this.analysisProgressHandlers = this.analysisProgressHandlers.filter(h => h !== handler) }
  }
  onAnalysisComplete(handler: EventHandler<AnalysisMetrics>) {
    this.analysisCompleteHandlers.push(handler)
    return () => { this.analysisCompleteHandlers = this.analysisCompleteHandlers.filter(h => h !== handler) }
  }
  onError(handler: EventHandler<string>) {
    this.errorHandlers.push(handler)
    return () => { this.errorHandlers = this.errorHandlers.filter(h => h !== handler) }
  }
  onSystemExplanation(handler: EventHandler<string>) {
    this.systemExplanationHandlers.push(handler)
    return () => { this.systemExplanationHandlers = this.systemExplanationHandlers.filter(h => h !== handler) }
  }
  onImpactAnalysis(handler: EventHandler<ImpactAnalysis>) {
    this.impactHandlers.push(handler)
    return () => { this.impactHandlers = this.impactHandlers.filter(h => h !== handler) }
  }
  onDebugFrame(handler: EventHandler<DebugFrame>) {
    this.debugFrameHandlers.push(handler)
    return () => { this.debugFrameHandlers = this.debugFrameHandlers.filter(h => h !== handler) }
  }
  onDebugSessionEnded(handler: EventHandler<void>) {
    this.debugSessionEndedHandlers.push(handler)
    return () => { this.debugSessionEndedHandlers = this.debugSessionEndedHandlers.filter(h => h !== handler) }
  }
  onDebugStateChanged(handler: EventHandler<DebugSessionState>) {
    this.debugStateChangedHandlers.push(handler)
    return () => { this.debugStateChangedHandlers = this.debugStateChangedHandlers.filter(h => h !== handler) }
  }
  onAutoRefreshStart(handler: EventHandler<void>) {
    this.autoRefreshStartHandlers.push(handler)
    return () => { this.autoRefreshStartHandlers = this.autoRefreshStartHandlers.filter(h => h !== handler) }
  }
  onEngineStatus(handler: EventHandler<EngineStatusPayload>) {
    this.engineStatusHandlers.push(handler)
    return () => { this.engineStatusHandlers = this.engineStatusHandlers.filter(h => h !== handler) }
  }
  onFixApplied(handler: EventHandler<{ issueId: string }>) {
    this.fixAppliedHandlers.push(handler)
    return () => { this.fixAppliedHandlers = this.fixAppliedHandlers.filter(h => h !== handler) }
  }

  // Send events to the plugin
  sendEvent(type: string, payload?: Record<string, unknown>) {
    const message = JSON.stringify({ type, payload: payload ?? {} })
    if (window.__jcefQuery__) {
      window.__jcefQuery__(message)
    }
  }

  nodeClicked(nodeId: string) { this.sendEvent('NODE_CLICKED', { nodeId }) }
  nodeDoubleClicked(nodeId: string) { this.sendEvent('NODE_DOUBLE_CLICKED', { nodeId }) }
  requestFix(issueId: string, nodeId: string) { this.sendEvent('FIX_REQUESTED', { issueId, nodeId }) }
  applyFix(issueId: string, fixId: string) { this.sendEvent('APPLY_FIX', { issueId, fixId }) }

  requestImpact(nodeId: string) { this.sendEvent('IMPACT_REQUESTED', { nodeId }) }
  requestSystemExplanation() { this.sendEvent('EXPLAIN_SYSTEM') }
  requestAnalysis() { this.sendEvent('ANALYZE') }
  cancelAnalysis() { this.sendEvent('CANCEL_ANALYSIS') }
  requestExport() { this.sendEvent('EXPORT_REPORT') }
  setBreakpoint(filePath: string, line: number) { this.sendEvent('BREAKPOINT_SET', { filePath, line }) }
  removeBreakpoint(filePath: string, line: number) { this.sendEvent('BREAKPOINT_REMOVED', { filePath, line }) }

  // Debug step controls
  debugStepOver() { this.sendEvent('DEBUG_STEP_OVER') }
  debugStepInto() { this.sendEvent('DEBUG_STEP_INTO') }
  debugStepOut() { this.sendEvent('DEBUG_STEP_OUT') }
  debugResume() { this.sendEvent('DEBUG_RESUME') }
  debugPause() { this.sendEvent('DEBUG_PAUSE') }

  isConnected(): boolean {
    return typeof window.__jcefQuery__ === 'function'
  }
}

export const bridge = new PluginBridge()
