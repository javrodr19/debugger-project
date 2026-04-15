import { createContext, useContext } from 'react'
import type { ProjectGraph, GraphNode, Issue, CodeFix, AnalysisMetrics, NodeStatus, DebugFrame, ViewMode, EngineStatusPayload, AnalysisProgressPayload } from '../types'

export type DebugSessionState = 'idle' | 'running' | 'paused'

export interface AppState {
  graph: ProjectGraph | null
  selectedNode: GraphNode | null
  selectedIssue: Issue | null
  pendingFixes: Record<string, CodeFix>  // issueId -> CodeFix
  loadingFix: string | null              // issueId currently being fetched
  applyingFix:  string | null            // issueId currently being applied; null when idle
  isAnalyzing: boolean
  analysisProgress: AnalysisProgressPayload | null
  metrics: AnalysisMetrics | null
  engineStatus: EngineStatusPayload | null
  systemExplanation: string | null
  error: string | null
  highlightedNodes: string[]
  explanations: Record<string, string>
  breakpoints: string[]
  debugFrame: DebugFrame | null
  debugState: DebugSessionState
  isAutoRefreshing: boolean
  viewMode: ViewMode
  focusedNodeId: string | null  // node whose expanded panel should be on top
}

export type AppAction =
  | { type: 'SET_GRAPH'; payload: ProjectGraph }
  | { type: 'SELECT_NODE'; payload: GraphNode | null }
  | { type: 'SELECT_ISSUE'; payload: Issue | null }
  | { type: 'SET_FIX'; payload: CodeFix }
  | { type: 'SET_LOADING_FIX'; payload: string | null }
  | { type: 'SET_APPLYING_FIX';  payload: string | null }
  | { type: 'FIX_APPLIED';       payload: string }          // payload is issueId
  | { type: 'ANALYSIS_START' }
  | { type: 'SET_ANALYSIS_PROGRESS'; payload: AnalysisProgressPayload }
  | { type: 'ANALYSIS_COMPLETE'; payload: AnalysisMetrics }
  | { type: 'SET_ENGINE_STATUS'; payload: EngineStatusPayload }
  | { type: 'SET_ERROR'; payload: string | null }
  | { type: 'SET_SYSTEM_EXPLANATION'; payload: string }
  | { type: 'SET_EXPLANATION'; payload: { issueId: string; explanation: string } }
  | { type: 'UPDATE_NODE_STATUS'; payload: { nodeId: string; status: NodeStatus } }
  | { type: 'SET_HIGHLIGHTED'; payload: string[] }
  | { type: 'TOGGLE_BREAKPOINT'; payload: string }
  | { type: 'SET_DEBUG_FRAME'; payload: DebugFrame }
  | { type: 'CLEAR_DEBUG_FRAME' }
  | { type: 'SET_DEBUG_STATE'; payload: DebugSessionState }
  | { type: 'SET_AUTO_REFRESHING'; payload: boolean }
  | { type: 'SET_VIEW_MODE'; payload: ViewMode }
  | { type: 'SET_FOCUSED_NODE'; payload: string | null }
  | { type: 'SET_ISSUES_FOR_FILE'; payload: { filePath: string; issues: Issue[] } }

export const initialState: AppState = {
  graph: null,
  selectedNode: null,
  selectedIssue: null,
  pendingFixes: {},
  loadingFix: null,
  applyingFix: null,
  isAnalyzing: false,
  analysisProgress: null,
  metrics: null,
  engineStatus: null,
  systemExplanation: null,
  error: null,
  highlightedNodes: [],
  explanations: {},
  breakpoints: [],
  debugFrame: null,
  debugState: 'idle',
  isAutoRefreshing: false,
  viewMode: 'neuromap',
  focusedNodeId: null,
}

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'SET_GRAPH':
      return { ...state, graph: action.payload, isAnalyzing: false, analysisProgress: null, isAutoRefreshing: false }

    case 'SELECT_NODE': {
      const node = action.payload
      if (!node) return { ...state, selectedNode: null, selectedIssue: null }
      const firstIssue = node.issues[0] ?? null
      return { ...state, selectedNode: node, selectedIssue: firstIssue }
    }

    case 'SELECT_ISSUE':
      return { ...state, selectedIssue: action.payload }

    case 'SET_FIX': {
      const fix = action.payload
      return {
        ...state,
        pendingFixes: { ...state.pendingFixes, [fix.issueId]: fix },
        loadingFix: null,
      }
    }

    case 'SET_LOADING_FIX':
      return { ...state, loadingFix: action.payload }

    case 'SET_APPLYING_FIX':
      return { ...state, applyingFix: action.payload }

    case 'FIX_APPLIED': {
      const { [action.payload]: _removed, ...remainingFixes } = state.pendingFixes
      return { ...state, pendingFixes: remainingFixes, applyingFix: null }
    }

    case 'ANALYSIS_START':
      return { ...state, isAnalyzing: true, analysisProgress: null, error: null }

    case 'SET_ANALYSIS_PROGRESS':
      return { ...state, analysisProgress: action.payload }

    case 'ANALYSIS_COMPLETE':
      return { ...state, isAnalyzing: false, analysisProgress: null, metrics: action.payload, isAutoRefreshing: false }

    case 'SET_ENGINE_STATUS':
      return { ...state, engineStatus: action.payload }

    case 'SET_ERROR':
      return { ...state, error: action.payload, isAnalyzing: false, analysisProgress: null }

    case 'SET_SYSTEM_EXPLANATION':
      return { ...state, systemExplanation: action.payload }

    case 'SET_EXPLANATION': {
      const { issueId, explanation } = action.payload
      const updatedGraph = state.graph ? updateIssueExplanation(state.graph, issueId, explanation) : state.graph
      const updatedSelectedIssue = state.selectedIssue?.id === issueId
        ? { ...state.selectedIssue, explanation }
        : state.selectedIssue
      return {
        ...state,
        graph: updatedGraph,
        selectedIssue: updatedSelectedIssue,
        explanations: { ...state.explanations, [issueId]: explanation },
      }
    }

    case 'UPDATE_NODE_STATUS': {
      if (!state.graph) return state
      const { nodeId, status } = action.payload
      const updatedNodes = state.graph.nodes.map(n =>
        n.id === nodeId ? { ...n, status } : n
      )
      return { ...state, graph: { ...state.graph, nodes: updatedNodes } }
    }

    case 'SET_HIGHLIGHTED':
      return { ...state, highlightedNodes: action.payload }

    case 'TOGGLE_BREAKPOINT': {
      const key = action.payload
      const exists = state.breakpoints.includes(key)
      return {
        ...state,
        breakpoints: exists
          ? state.breakpoints.filter(b => b !== key)
          : [...state.breakpoints, key],
      }
    }

    case 'SET_DEBUG_FRAME':
      return { ...state, debugFrame: action.payload, debugState: 'paused' }

    case 'CLEAR_DEBUG_FRAME':
      return { ...state, debugFrame: null, debugState: 'idle' }

    case 'SET_DEBUG_STATE':
      return { ...state, debugState: action.payload }

    case 'SET_AUTO_REFRESHING':
      return { ...state, isAutoRefreshing: action.payload }

    case 'SET_VIEW_MODE':
      return { ...state, viewMode: action.payload }

    case 'SET_FOCUSED_NODE':
      return { ...state, focusedNodeId: action.payload }

    case 'SET_ISSUES_FOR_FILE': {
      if (!state.graph) return state
      const { filePath, issues } = action.payload
      const updatedNodes = state.graph.nodes.map(n =>
        n.filePath === filePath ? { ...n, issues } : n
      )
      const isSelectedNodeMatched = state.selectedNode?.filePath === filePath
      const updatedSelectedNode = isSelectedNodeMatched
        ? { ...state.selectedNode!, issues }
        : state.selectedNode
      
      let updatedSelectedIssue = state.selectedIssue
      if (isSelectedNodeMatched) {
        // If we updated the currently selected node, we might need to update the selected issue too
        updatedSelectedIssue = issues.find(i => i.id === state.selectedIssue?.id) || issues[0] || null
      }
      
      return { 
        ...state, 
        graph: { ...state.graph, nodes: updatedNodes },
        selectedNode: updatedSelectedNode,
        selectedIssue: updatedSelectedIssue
      }
    }

    default:
      return state
  }
}

function updateIssueExplanation(graph: ProjectGraph, issueId: string, explanation: string): ProjectGraph {
  return {
    ...graph,
    nodes: graph.nodes.map(node => ({
      ...node,
      issues: node.issues.map(issue =>
        issue.id === issueId ? { ...issue, explanation } : issue
      ),
    })),
  }
}

export const AppContext = createContext<{
  state: AppState
  dispatch: React.Dispatch<AppAction>
} | null>(null)

export function useAppStore() {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useAppStore must be used within AppProvider')
  return ctx
}
