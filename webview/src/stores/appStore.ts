import { createContext, useContext } from 'react'
import type { ProjectGraph, GraphNode, Issue, CodeFix, AnalysisMetrics, NodeStatus } from '../types'

export interface AppState {
  graph: ProjectGraph | null
  selectedNode: GraphNode | null
  selectedIssue: Issue | null
  pendingFix: CodeFix | null
  isAnalyzing: boolean
  metrics: AnalysisMetrics | null
  systemExplanation: string | null
  error: string | null
  highlightedNodes: string[]
  explanations: Record<string, string>
}

export type AppAction =
  | { type: 'SET_GRAPH'; payload: ProjectGraph }
  | { type: 'SELECT_NODE'; payload: GraphNode | null }
  | { type: 'SELECT_ISSUE'; payload: Issue | null }
  | { type: 'SET_FIX'; payload: CodeFix | null }
  | { type: 'ANALYSIS_START' }
  | { type: 'ANALYSIS_COMPLETE'; payload: AnalysisMetrics }
  | { type: 'SET_ERROR'; payload: string | null }
  | { type: 'SET_SYSTEM_EXPLANATION'; payload: string }
  | { type: 'SET_EXPLANATION'; payload: { issueId: string; explanation: string } }
  | { type: 'UPDATE_NODE_STATUS'; payload: { nodeId: string; status: NodeStatus } }
  | { type: 'SET_HIGHLIGHTED'; payload: string[] }

export const initialState: AppState = {
  graph: null,
  selectedNode: null,
  selectedIssue: null,
  pendingFix: null,
  isAnalyzing: false,
  metrics: null,
  systemExplanation: null,
  error: null,
  highlightedNodes: [],
  explanations: {},
}

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'SET_GRAPH':
      return { ...state, graph: action.payload, isAnalyzing: false }

    case 'SELECT_NODE': {
      const node = action.payload
      if (!node) return { ...state, selectedNode: null, selectedIssue: null }
      const firstIssue = node.issues[0] ?? null
      return { ...state, selectedNode: node, selectedIssue: firstIssue }
    }

    case 'SELECT_ISSUE':
      return { ...state, selectedIssue: action.payload }

    case 'SET_FIX':
      return { ...state, pendingFix: action.payload }

    case 'ANALYSIS_START':
      return { ...state, isAnalyzing: true, error: null }

    case 'ANALYSIS_COMPLETE':
      return { ...state, isAnalyzing: false, metrics: action.payload }

    case 'SET_ERROR':
      return { ...state, error: action.payload, isAnalyzing: false }

    case 'SET_SYSTEM_EXPLANATION':
      return { ...state, systemExplanation: action.payload }

    case 'SET_EXPLANATION': {
      const { issueId, explanation } = action.payload
      // Also update the issue in the graph if present
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
