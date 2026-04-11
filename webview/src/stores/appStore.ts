import { createContext, useContext } from 'react'
import type { ProjectGraph, GraphNode, Issue, CodeFix, AnalysisMetrics, NodeStatus, DebugFrame, ViewMode } from '../types'

export type DebugSessionState = 'idle' | 'running' | 'paused'

export interface AppState {
  graph: ProjectGraph | null
  selectedNode: GraphNode | null
  selectedIssue: Issue | null
  pendingFixes: Record<string, CodeFix>  // issueId -> CodeFix
  loadingFix: string | null              // issueId currently being fetched
  isAnalyzing: boolean
  metrics: AnalysisMetrics | null
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
  | { type: 'ANALYSIS_START' }
  | { type: 'ANALYSIS_COMPLETE'; payload: AnalysisMetrics }
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

export const initialState: AppState = {
  graph: null,
  selectedNode: null,
  selectedIssue: null,
  pendingFixes: {},
  loadingFix: null,
  isAnalyzing: false,
  metrics: null,
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
      return { ...state, graph: action.payload, isAnalyzing: false, isAutoRefreshing: false }

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

    case 'ANALYSIS_START':
      return { ...state, isAnalyzing: true, error: null }

    case 'ANALYSIS_COMPLETE':
      return { ...state, isAnalyzing: false, metrics: action.payload, isAutoRefreshing: false }

    case 'SET_ERROR':
      return { ...state, error: action.payload, isAnalyzing: false }

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
