import type { ProjectGraph, Issue, CodeFix, AnalysisMetrics, ImpactAnalysis, NodeStatus } from '../types'

type EventHandler<T> = (data: T) => void

interface GhostDebuggerAPI {
  onGraphUpdate: (data: ProjectGraph) => void
  onExplanation: (data: { issueId: string; explanation: string }) => void
  onFixSuggestion: (fix: CodeFix) => void
  onNodeUpdate: (data: { nodeId: string; status: NodeStatus }) => void
  onAnalysisStart: () => void
  onAnalysisComplete: (metrics: AnalysisMetrics) => void
  onError: (message: string) => void
  onSystemExplanation: (explanation: string) => void
  onImpactAnalysis: (data: ImpactAnalysis) => void
  __ready__?: () => void
}

declare global {
  interface Window {
    __ghostdebugger__: GhostDebuggerAPI
    __jcefQuery__: (msg: string) => void
  }
}

class PluginBridge {
  private graphHandlers: EventHandler<ProjectGraph>[] = []
  private explanationHandlers: EventHandler<{ issueId: string; explanation: string }>[] = []
  private fixHandlers: EventHandler<CodeFix>[] = []
  private nodeUpdateHandlers: EventHandler<{ nodeId: string; status: NodeStatus }>[] = []
  private analysisStartHandlers: EventHandler<void>[] = []
  private analysisCompleteHandlers: EventHandler<AnalysisMetrics>[] = []
  private errorHandlers: EventHandler<string>[] = []
  private systemExplanationHandlers: EventHandler<string>[] = []
  private impactHandlers: EventHandler<ImpactAnalysis>[] = []

  constructor() {
    this.setupAPI()
  }

  private setupAPI() {
    window.__ghostdebugger__ = {
      onGraphUpdate: (data) => this.graphHandlers.forEach(h => h(data)),
      onExplanation: (data) => this.explanationHandlers.forEach(h => h(data)),
      onFixSuggestion: (fix) => this.fixHandlers.forEach(h => h(fix)),
      onNodeUpdate: (data) => this.nodeUpdateHandlers.forEach(h => h(data)),
      onAnalysisStart: () => this.analysisStartHandlers.forEach(h => h()),
      onAnalysisComplete: (metrics) => this.analysisCompleteHandlers.forEach(h => h(metrics)),
      onError: (msg) => this.errorHandlers.forEach(h => h(msg)),
      onSystemExplanation: (exp) => this.systemExplanationHandlers.forEach(h => h(exp)),
      onImpactAnalysis: (data) => this.impactHandlers.forEach(h => h(data)),
      __ready__: () => console.log('[GhostDebugger] API ready'),
    }
  }

  // Event subscriptions
  onGraphUpdate(handler: EventHandler<ProjectGraph>) { this.graphHandlers.push(handler) }
  onExplanation(handler: EventHandler<{ issueId: string; explanation: string }>) { this.explanationHandlers.push(handler) }
  onFixSuggestion(handler: EventHandler<CodeFix>) { this.fixHandlers.push(handler) }
  onNodeUpdate(handler: EventHandler<{ nodeId: string; status: NodeStatus }>) { this.nodeUpdateHandlers.push(handler) }
  onAnalysisStart(handler: EventHandler<void>) { this.analysisStartHandlers.push(handler) }
  onAnalysisComplete(handler: EventHandler<AnalysisMetrics>) { this.analysisCompleteHandlers.push(handler) }
  onError(handler: EventHandler<string>) { this.errorHandlers.push(handler) }
  onSystemExplanation(handler: EventHandler<string>) { this.systemExplanationHandlers.push(handler) }
  onImpactAnalysis(handler: EventHandler<ImpactAnalysis>) { this.impactHandlers.push(handler) }

  // Send events to the plugin
  sendEvent(type: string, payload?: Record<string, unknown>) {
    const message = JSON.stringify({ type, payload: payload ?? {} })
    if (window.__jcefQuery__) {
      window.__jcefQuery__(message)
    } else {
      console.log('[Bridge] Would send:', message)
    }
  }

  nodeClicked(nodeId: string) { this.sendEvent('NODE_CLICKED', { nodeId }) }
  requestFix(issueId: string, nodeId: string) { this.sendEvent('FIX_REQUESTED', { issueId, nodeId }) }
  requestSimulation(entryNodeId: string) { this.sendEvent('SIMULATE_REQUESTED', { entryNodeId }) }
  askWhatIf(question: string) { this.sendEvent('WHAT_IF', { question }) }
  requestImpact(nodeId: string) { this.sendEvent('IMPACT_REQUESTED', { nodeId }) }
  requestSystemExplanation() { this.sendEvent('EXPLAIN_SYSTEM') }
  requestAnalysis() { this.sendEvent('ANALYZE') }

  isConnected(): boolean {
    return typeof window.__jcefQuery__ === 'function'
  }
}

export const bridge = new PluginBridge()
