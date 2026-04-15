export type NodeStatus = 'HEALTHY' | 'WARNING' | 'ERROR'
export type NodeType = 'FILE' | 'FUNCTION' | 'CLASS' | 'COMPONENT' | 'HOOK' | 'API_ROUTE' | 'MODULE' | 'SERVICE'
export type EdgeType = 'IMPORT' | 'CALL' | 'DATA_FLOW' | 'STATE_DEPENDENCY' | 'INHERITANCE'
export type IssueSeverity = 'ERROR' | 'WARNING' | 'INFO'
export type IssueType =
  | 'NULL_SAFETY'
  | 'CIRCULAR_DEPENDENCY'
  | 'ASYNC_FLOW'
  | 'UNHANDLED_PROMISE'
  | 'STATE_BEFORE_INIT'
  | 'HIGH_COMPLEXITY'
  | 'MISSING_ERROR_HANDLING'
  | 'DEAD_CODE'
  | 'RESOURCE_LEAK'
  | 'MEMORY_LEAK'
  | 'ARCHITECTURE'

// ── Phase 2 provenance types ──────────────────────────────────────────────────
export type IssueSource    = 'STATIC' | 'AI_CLOUD' | 'AI_LOCAL'
export type EngineProvider = 'STATIC' | 'OPENAI' | 'OLLAMA'
export type EngineStatus   = 'ONLINE' | 'OFFLINE' | 'DEGRADED' | 'FALLBACK_TO_STATIC' | 'DISABLED'

export interface EngineStatusPayload {
  provider:  EngineProvider
  status:    EngineStatus
  message?:  string
  latencyMs?: number
}

export interface AnalysisProgressPayload {
  text: string
  fraction: number
}

export interface Issue {
  id: string
  type: IssueType
  severity: IssueSeverity
  title: string
  description: string
  filePath: string
  line: number
  column: number
  codeSnippet: string
  affectedNodes: string[]
  explanation?: string
  ruleId?:     string
  sources?:    IssueSource[]
  providers?:  EngineProvider[]
  confidence?: number
}

export interface CodeFix {
  id: string
  issueId: string
  description: string
  originalCode: string
  fixedCode: string
  filePath: string
  lineStart: number
  lineEnd: number
  isDeterministic?: boolean
  confidence?:      number
}

export interface FunctionInfo {
  name: string
  line: number
  isAsync: boolean
}

export interface VariableInfo {
  name: string
  line: number
  kind: string // const | let | var | val
}

export interface NodePosition {
  x: number
  y: number
}

export interface GraphNode {
  id: string
  type: NodeType
  name: string
  filePath: string
  lineStart: number
  lineEnd: number
  complexity: number
  status: NodeStatus
  issues: Issue[]
  dependencies: string[]
  dependents: string[]
  position?: NodePosition
  functions: FunctionInfo[]
  variables: VariableInfo[]
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  type: EdgeType
  weight: number
  animated: boolean
  isCycle?: boolean
}

export interface GraphMetadata {
  projectName: string
  totalFiles: number
  totalIssues: number
  analysisTimestamp: number
  healthScore: number
  cycles?: string[][]
}

export interface ProjectGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  metadata: GraphMetadata
}

export interface AnalysisMetrics {
  errorCount: number
  warningCount: number
  healthScore: number
}

export interface ImpactAnalysis {
  nodeId: string
  affectedNodes: string[]
}

export interface DebugVariable {
  name: string
  value: string
  type: string
}

export interface DebugFrame {
  nodeId: string
  filePath: string
  line: number
  variables: DebugVariable[]
}

export type ViewMode = 'neuromap' | 'pixelcity'
