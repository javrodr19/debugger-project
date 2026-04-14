import { useEffect, useReducer, useCallback } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { AnimatePresence, motion } from 'framer-motion'
import { NeuroMap } from './components/neuromap/NeuroMap'
import { DetailPanel } from './components/detail-panel/DetailPanel'
import { StatusBar } from './components/layout/StatusBar'
import { PixelCity } from './components/pixelcity/PixelCity'
import { AppContext, appReducer, initialState } from './stores/appStore'
import { bridge } from './bridge/pluginBridge'

export default function App() {
  const [state, dispatch] = useReducer(appReducer, initialState)

  useEffect(() => {
    const unsubGraph    = bridge.onGraphUpdate(graph => dispatch({ type: 'SET_GRAPH', payload: graph }))
    const unsubExpl     = bridge.onExplanation(({ issueId, explanation }) =>
      dispatch({ type: 'SET_EXPLANATION', payload: { issueId, explanation } })
    )
    const unsubFix      = bridge.onFixSuggestion(fix => dispatch({ type: 'SET_FIX', payload: fix }))
    const unsubNode     = bridge.onNodeUpdate(({ nodeId, status }) =>
      dispatch({ type: 'UPDATE_NODE_STATUS', payload: { nodeId, status } })
    )
    const unsubStart    = bridge.onAnalysisStart(() => dispatch({ type: 'ANALYSIS_START' }))
    const unsubComplete = bridge.onAnalysisComplete(metrics => dispatch({ type: 'ANALYSIS_COMPLETE', payload: metrics }))
    
    const unsubError    = bridge.onError(message => {
      dispatch({ type: 'SET_ERROR', payload: message })
      setTimeout(() => dispatch({ type: 'SET_ERROR', payload: null }), 5000)
    })

    const unsubSystem   = bridge.onSystemExplanation(explanation =>
      dispatch({ type: 'SET_SYSTEM_EXPLANATION', payload: explanation })
    )

    const unsubImpact   = bridge.onImpactAnalysis(({ nodeId, affectedNodes }) => {
      dispatch({ type: 'SET_HIGHLIGHTED', payload: [nodeId, ...affectedNodes] })
    })

    const unsubFrame    = bridge.onDebugFrame(frame => dispatch({ type: 'SET_DEBUG_FRAME', payload: frame }))
    const unsubEnd      = bridge.onDebugSessionEnded(() => dispatch({ type: 'CLEAR_DEBUG_FRAME' }))
    const unsubState    = bridge.onDebugStateChanged(s => dispatch({ type: 'SET_DEBUG_STATE', payload: s }))
    const unsubAuto     = bridge.onAutoRefreshStart(() => dispatch({ type: 'SET_AUTO_REFRESHING', payload: true }))

    const unsubEngineStatus = bridge.onEngineStatus(payload =>
      dispatch({ type: 'SET_ENGINE_STATUS', payload })
    )

    const unsubFixApplied = bridge.onFixApplied(({ issueId }) =>
      dispatch({ type: 'FIX_APPLIED', payload: issueId })
    )

    return () => {
      unsubGraph(); unsubExpl(); unsubFix(); unsubNode();
      unsubStart(); unsubComplete(); unsubError(); unsubSystem();
      unsubImpact(); unsubFrame(); unsubEnd(); unsubState(); unsubAuto();
      unsubEngineStatus(); unsubFixApplied();
    }
  }, [])

  const handleAnalyze = useCallback(() => bridge.requestAnalysis(), [])
  const handleOverview = useCallback(() => dispatch({ type: 'SELECT_NODE', payload: null }), [dispatch])
  const handleExport = useCallback(() => bridge.requestExport(), [])
  const handleToggleView = useCallback(() => {
    dispatch({
      type: 'SET_VIEW_MODE',
      payload: state.viewMode === 'neuromap' ? 'pixelcity' : 'neuromap'
    })
  }, [state.viewMode])

  const isDebugging = state.debugState !== 'idle'
  const isPaused = state.debugState === 'paused'

  return (
    <AppContext.Provider value={{ state, dispatch }}>
      <div style={{
        width: '100vw', height: '100vh',
        display: 'flex', flexDirection: 'column',
        background: 'var(--bg-base)', color: 'var(--fg-primary)',
        overflow: 'hidden',
        fontFamily: 'var(--font-ui)',
      }}>

        <StatusBar
          isAnalyzing={state.isAnalyzing}
          metrics={state.metrics}
          projectName={state.graph?.metadata.projectName}
          totalNodes={state.graph?.nodes.length}
          isAutoRefreshing={state.isAutoRefreshing}
          engineStatus={state.engineStatus}
        />

        {/* Main Toolbar */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '6px 12px',
          background: 'var(--bg-surface)',
          borderBottom: '1px solid var(--border)',
          flexShrink: 0,
        }}>
          <ToolbarButton onClick={handleAnalyze} disabled={state.isAnalyzing} primary>
            {state.isAnalyzing ? '⟳ Analyzing…' : '▶  Analyze Project'}
          </ToolbarButton>

          <ToolbarButton onClick={handleOverview}>
            ≡  Overview
          </ToolbarButton>

          <ToolbarButton onClick={handleExport} disabled={!state.graph}>
            📄 Export Report
          </ToolbarButton>

          <ToolbarButton onClick={handleToggleView}>
            {state.viewMode === 'neuromap' ? '🏙 Pixel City' : '🧠 NeuroMap'}
          </ToolbarButton>

          <div style={{ flex: 1 }} />

          {/* Auto-refresh indicator */}
          {state.isAutoRefreshing && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginRight: 8 }}>
              <div style={{
                width: 6, height: 6, borderRadius: '50%',
                background: 'var(--accent)',
                animation: 'pulse-glow-blue 1.5s ease-in-out infinite',
              }} />
              <span style={{ color: 'var(--accent)', fontSize: 9 }}>Auto-refreshing</span>
            </div>
          )}

          {/* Legend */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 9, color: 'var(--fg-muted)' }}>
            <LegendDot color="var(--error-text)" label="Error" />
            <LegendDot color="var(--warn-text)" label="Warning" />
            <LegendDot color="var(--ok-text)" label="Healthy" />
            {state.viewMode === 'neuromap' && (
              <LegendDot color="#f0883e" label="Cycle" />
            )}
          </div>
        </div>

        {/* Debug Controls Toolbar — appears when debugging */}
        <AnimatePresence>
          {isDebugging && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.15 }}
              style={{ overflow: 'hidden' }}
            >
              <div style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '5px 12px',
                background: 'rgba(86,212,221,0.06)',
                borderBottom: '1px solid rgba(86,212,221,0.15)',
                flexShrink: 0,
              }}>
                {/* Debug status indicator */}
                <div style={{
                  display: 'flex', alignItems: 'center', gap: 5,
                  background: 'rgba(86,212,221,0.1)',
                  border: '1px solid rgba(86,212,221,0.25)',
                  padding: '3px 8px',
                  borderRadius: 5,
                  marginRight: 4,
                }}>
                  <div style={{
                    width: 6, height: 6, borderRadius: '50%',
                    background: isPaused ? 'var(--warn-text)' : 'var(--ok-text)',
                    animation: isPaused ? undefined : 'pulse-glow-blue 1s ease-in-out infinite',
                  }} />
                  <span style={{ color: '#56d4dd', fontSize: 9, fontWeight: 700, letterSpacing: '0.06em' }}>
                    {isPaused ? 'PAUSED' : 'RUNNING'}
                  </span>
                </div>

                {/* Step controls */}
                <DebugButton onClick={() => bridge.debugStepOver()} disabled={!isPaused} title="Step Over (F8)">
                  ⏭ Step Over
                </DebugButton>
                <DebugButton onClick={() => bridge.debugStepInto()} disabled={!isPaused} title="Step Into (F7)">
                  ⬇ Step Into
                </DebugButton>
                <DebugButton onClick={() => bridge.debugStepOut()} disabled={!isPaused} title="Step Out (Shift+F8)">
                  ⬆ Step Out
                </DebugButton>

                <div style={{ width: 1, height: 16, background: 'rgba(86,212,221,0.2)', margin: '0 2px' }} />

                <DebugButton
                  onClick={() => isPaused ? bridge.debugResume() : bridge.debugPause()}
                  title={isPaused ? 'Resume (F9)' : 'Pause'}
                  highlight
                >
                  {isPaused ? '▶ Resume' : '⏸ Pause'}
                </DebugButton>

                <div style={{ flex: 1 }} />

                {/* Current debug location */}
                {state.debugFrame && (
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    color: 'var(--fg-secondary)', fontSize: 9, fontFamily: 'var(--font-code)',
                  }}>
                    <span style={{ color: '#56d4dd' }}>📍</span>
                    <span>
                      {state.debugFrame.filePath.split('/').pop()}:{state.debugFrame.line}
                    </span>
                    {state.debugFrame.variables.length > 0 && (
                      <span style={{
                        color: '#56d4dd',
                        background: 'rgba(86,212,221,0.1)',
                        border: '1px solid rgba(86,212,221,0.2)',
                        padding: '1px 5px',
                        borderRadius: 3,
                        fontSize: 8,
                      }}>
                        {state.debugFrame.variables.length} vars
                      </span>
                    )}
                  </div>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Main */}
        <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
          {/* Canvas */}
          <div style={{ flex: 1, position: 'relative', minWidth: 0, flexGrow: 1 }}>
            {state.graph ? (
              state.viewMode === 'neuromap' ? (
                <ReactFlowProvider>
                  <NeuroMap graph={state.graph} />
                </ReactFlowProvider>
              ) : (
                <PixelCity graph={state.graph} />
              )
            ) : (
              <EmptyState isAnalyzing={state.isAnalyzing} onAnalyze={handleAnalyze} />
            )}
          </div>

          {/* Detail panel */}
          <div style={{
            width: 260,
            flexShrink: 0,
            borderLeft: '1px solid var(--border)',
            background: 'var(--bg-base)',
            overflowY: 'auto',
            position: 'relative',
            zIndex: 100, // Ensure it's on top of city/map layers
          }}>
            <DetailPanel key={state.selectedNode?.id || 'overview'} />
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
                background: 'var(--bg-elevated)',
                border: '1px solid var(--error-text)',
                color: 'var(--error-text)',
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
        background: primary ? 'var(--ok-text)' : 'var(--bg-elevated)',
        border: `1px solid ${primary ? 'var(--ok-border)' : 'var(--border)'}`,
        color: primary ? '#ffffff' : 'var(--fg-secondary)',
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

function DebugButton({
  children, onClick, disabled, title, highlight,
}: {
  children: React.ReactNode
  onClick: () => void
  disabled?: boolean
  title?: string
  highlight?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      style={{
        display: 'flex', alignItems: 'center', gap: 4,
        padding: '4px 10px',
        background: highlight ? 'rgba(86,212,221,0.12)' : 'var(--bg-elevated)',
        border: `1px solid ${highlight ? 'rgba(86,212,221,0.3)' : 'var(--border)'}`,
        color: highlight ? '#56d4dd' : 'var(--fg-secondary)',
        fontSize: 9, fontWeight: 600,
        borderRadius: 5, cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1,
        fontFamily: 'inherit',
        transition: 'all 0.1s',
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
      background: 'var(--bg-base)',
    }}>
      <div style={{
        width: 56, height: 56, borderRadius: 14,
        background: 'var(--bg-surface)', border: '1px solid var(--border)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 28,
      }}>
        <div style={{
          width: 32, height: 32, borderRadius: 6,
          border: '2px solid var(--fg-primary)',
          position: 'relative'
        }}>
           <div style={{
             position: 'absolute', top: '50%', left: '50%',
             transform: 'translate(-50%, -50%)',
             width: 12, height: 12, borderRadius: '50%',
             border: '2px solid var(--fg-primary)'
           }} />
        </div>
      </div>
      <div>
        <div style={{ color: 'var(--fg-primary)', fontSize: 15, fontWeight: 700, marginBottom: 8 }}>
          Aegis Debug
        </div>
        <p style={{ color: 'var(--fg-muted)', fontSize: 10, lineHeight: 1.7, maxWidth: 260, margin: 0 }}>
          Analyze your project to visualize the dependency graph, detect issues, and get AI-powered fix suggestions.
        </p>
      </div>
      {!isAnalyzing && (
        <button
          onClick={onAnalyze}
          style={{
            padding: '8px 24px',
            background: 'var(--ok-text)', border: '1px solid var(--ok-border)',
            color: '#fff', fontSize: 11, fontWeight: 700,
            borderRadius: 8, cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          ▶  Start Analysis
        </button>
      )}
      {isAnalyzing && (
        <span style={{ color: 'var(--accent)', fontSize: 11 }}>⟳ Analyzing project…</span>
      )}
    </div>
  )
}
