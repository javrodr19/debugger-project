import { useEffect, useReducer, useCallback } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { AnimatePresence, motion } from 'framer-motion'
import { NeuroMap } from './components/neuromap/NeuroMap'
import { DetailPanel } from './components/detail-panel/DetailPanel'
import { StatusBar } from './components/layout/StatusBar'
import { AppContext, appReducer, initialState } from './stores/appStore'
import { bridge } from './bridge/pluginBridge'

export default function App() {
  const [state, dispatch] = useReducer(appReducer, initialState)

  useEffect(() => {
    bridge.onGraphUpdate(graph => dispatch({ type: 'SET_GRAPH', payload: graph }))

    bridge.onExplanation(({ issueId, explanation }) =>
      dispatch({ type: 'SET_EXPLANATION', payload: { issueId, explanation } })
    )

    bridge.onFixSuggestion(fix => dispatch({ type: 'SET_FIX', payload: fix }))

    bridge.onNodeUpdate(({ nodeId, status }) =>
      dispatch({ type: 'UPDATE_NODE_STATUS', payload: { nodeId, status } })
    )

    bridge.onAnalysisStart(() => dispatch({ type: 'ANALYSIS_START' }))

    bridge.onAnalysisComplete(metrics => dispatch({ type: 'ANALYSIS_COMPLETE', payload: metrics }))

    bridge.onError(message => {
      dispatch({ type: 'SET_ERROR', payload: message })
      setTimeout(() => dispatch({ type: 'SET_ERROR', payload: null }), 5000)
    })

    bridge.onSystemExplanation(explanation =>
      dispatch({ type: 'SET_SYSTEM_EXPLANATION', payload: explanation })
    )

    bridge.onImpactAnalysis(({ nodeId, affectedNodes }) => {
      dispatch({ type: 'SET_HIGHLIGHTED', payload: [nodeId, ...affectedNodes] })
      setTimeout(() => dispatch({ type: 'SET_HIGHLIGHTED', payload: [] }), 3000)
    })

    console.log('[GhostDebugger] ready, bridge connected:', bridge.isConnected())
  }, [])

  const handleAnalyze = useCallback(() => bridge.requestAnalysis(), [])
  const handleOverview = useCallback(() => dispatch({ type: 'SELECT_NODE', payload: null }), [dispatch])

  return (
    <AppContext.Provider value={{ state, dispatch }}>
      <div style={{
        width: '100vw', height: '100vh',
        display: 'flex', flexDirection: 'column',
        background: '#0d1117', color: '#e6edf3',
        overflow: 'hidden',
        fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
      }}>

        <StatusBar
          isAnalyzing={state.isAnalyzing}
          metrics={state.metrics}
          projectName={state.graph?.metadata.projectName}
          totalNodes={state.graph?.nodes.length}
        />

        {/* Toolbar */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '6px 12px',
          background: '#161b22',
          borderBottom: '1px solid #21262d',
          flexShrink: 0,
        }}>
          <ToolbarButton
            onClick={handleAnalyze}
            disabled={state.isAnalyzing}
            primary
          >
            {state.isAnalyzing ? '⟳ Analyzing…' : '▶  Analyze Project'}
          </ToolbarButton>

          <ToolbarButton onClick={handleOverview}>
            ≡  Overview
          </ToolbarButton>

          <div style={{ flex: 1 }} />

          {/* Legend */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 9, color: '#6e7681' }}>
            <LegendDot color="#f85149" label="Error" />
            <LegendDot color="#d29922" label="Warning" />
            <LegendDot color="#3fb950" label="Healthy" />
          </div>
        </div>

        {/* Main */}
        <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
          {/* Canvas */}
          <div style={{ flex: 1, position: 'relative' }}>
            {state.graph ? (
              <ReactFlowProvider>
                <NeuroMap graph={state.graph} />
              </ReactFlowProvider>
            ) : (
              <EmptyState isAnalyzing={state.isAnalyzing} onAnalyze={handleAnalyze} />
            )}
          </div>

          {/* Detail panel */}
          <div style={{
            width: 260,
            flexShrink: 0,
            borderLeft: '1px solid #21262d',
            background: '#0d1117',
            overflowY: 'auto',
          }}>
            <DetailPanel />
          </div>
        </div>

        {/* Error toast */}
        <AnimatePresence>
          {state.error && (
            <motion.div
              initial={{ opacity: 0, y: 30 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 30 }}
              style={{
                position: 'fixed', bottom: 16,
                left: '50%', transform: 'translateX(-50%)',
                background: '#21262d',
                border: '1px solid #f85149',
                color: '#f85149',
                fontSize: 10, padding: '8px 16px',
                borderRadius: 8, maxWidth: 320,
                textAlign: 'center',
                zIndex: 9999,
                fontFamily: 'inherit',
              }}
            >
              {state.error}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </AppContext.Provider>
  )
}

function ToolbarButton({
  children, onClick, disabled, primary,
}: {
  children: React.ReactNode
  onClick: () => void
  disabled?: boolean
  primary?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      style={{
        display: 'flex', alignItems: 'center', gap: 5,
        padding: '5px 12px',
        background: primary ? '#238636' : '#21262d',
        border: `1px solid ${primary ? '#2ea043' : '#30363d'}`,
        color: primary ? '#ffffff' : '#8b949e',
        fontSize: 10, fontWeight: 600,
        borderRadius: 6, cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        letterSpacing: '0.03em',
        fontFamily: 'inherit',
        transition: 'opacity 0.1s',
      }}
    >
      {children}
    </button>
  )
}

function LegendDot({ color, label }: { color: string; label: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <div style={{ width: 6, height: 6, borderRadius: '50%', background: color, flexShrink: 0 }} />
      <span>{label}</span>
    </div>
  )
}

function EmptyState({ isAnalyzing, onAnalyze }: { isAnalyzing: boolean; onAnalyze: () => void }) {
  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center',
      gap: 20, textAlign: 'center', padding: 32,
      background: '#0d1117',
    }}>
      <div style={{
        width: 56, height: 56, borderRadius: 14,
        background: '#161b22', border: '1px solid #30363d',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 28,
      }}>
        👻
      </div>
      <div>
        <div style={{ color: '#e6edf3', fontSize: 15, fontWeight: 700, marginBottom: 8 }}>
          GhostDebugger
        </div>
        <p style={{ color: '#6e7681', fontSize: 10, lineHeight: 1.7, maxWidth: 260, margin: 0 }}>
          Analyze your project to visualize the dependency graph, detect issues, and get AI-powered fix suggestions.
        </p>
      </div>
      {!isAnalyzing && (
        <button
          onClick={onAnalyze}
          style={{
            padding: '8px 24px',
            background: '#238636', border: '1px solid #2ea043',
            color: '#fff', fontSize: 11, fontWeight: 700,
            borderRadius: 8, cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          ▶  Start Analysis
        </button>
      )}
      {isAnalyzing && (
        <span style={{ color: '#388bfd', fontSize: 11 }}>⟳ Analyzing project…</span>
      )}
    </div>
  )
}
