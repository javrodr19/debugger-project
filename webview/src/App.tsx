import { useEffect, useReducer, useCallback } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { motion, AnimatePresence } from 'framer-motion'
import { NeuroMap } from './components/neuromap/NeuroMap'
import { DetailPanel } from './components/detail-panel/DetailPanel'
import { StatusBar } from './components/layout/StatusBar'
import { AppContext, appReducer, initialState } from './stores/appStore'
import { bridge } from './bridge/pluginBridge'
import { Loader2, Play, MessageSquare } from 'lucide-react'

export default function App() {
  const [state, dispatch] = useReducer(appReducer, initialState)

  useEffect(() => {
    // Subscribe to bridge events
    bridge.onGraphUpdate((graph) => {
      dispatch({ type: 'SET_GRAPH', payload: graph })
    })

    bridge.onExplanation(({ issueId, explanation }) => {
      dispatch({ type: 'SET_EXPLANATION', payload: { issueId, explanation } })
    })

    bridge.onFixSuggestion((fix) => {
      dispatch({ type: 'SET_FIX', payload: fix })
    })

    bridge.onNodeUpdate(({ nodeId, status }) => {
      dispatch({ type: 'UPDATE_NODE_STATUS', payload: { nodeId, status } })
    })

    bridge.onAnalysisStart(() => {
      dispatch({ type: 'ANALYSIS_START' })
    })

    bridge.onAnalysisComplete((metrics) => {
      dispatch({ type: 'ANALYSIS_COMPLETE', payload: metrics })
    })

    bridge.onError((message) => {
      dispatch({ type: 'SET_ERROR', payload: message })
      setTimeout(() => dispatch({ type: 'SET_ERROR', payload: null }), 5000)
    })

    bridge.onSystemExplanation((explanation) => {
      dispatch({ type: 'SET_SYSTEM_EXPLANATION', payload: explanation })
    })

    bridge.onImpactAnalysis(({ nodeId, affectedNodes }) => {
      dispatch({ type: 'SET_HIGHLIGHTED', payload: [nodeId, ...affectedNodes] })
      setTimeout(() => dispatch({ type: 'SET_HIGHLIGHTED', payload: [] }), 3000)
    })

    console.log('[GhostDebugger] App initialized, bridge connected:', bridge.isConnected())
  }, [])

  const handleAnalyze = useCallback(() => {
    bridge.requestAnalysis()
  }, [])

  const handleExplainSystem = useCallback(() => {
    bridge.requestSystemExplanation()
  }, [])

  return (
    <AppContext.Provider value={{ state, dispatch }}>
      <div className="w-screen h-screen flex flex-col bg-gray-950 text-gray-100 overflow-hidden font-sans">
        {/* Status Bar */}
        <StatusBar
          isAnalyzing={state.isAnalyzing}
          metrics={state.metrics}
          projectName={state.graph?.metadata.projectName}
          totalNodes={state.graph?.nodes.length}
        />

        {/* Toolbar */}
        <div className="flex items-center gap-2 px-4 py-2 bg-gray-900 border-b border-gray-800">
          <button
            onClick={handleAnalyze}
            disabled={state.isAnalyzing}
            className="flex items-center gap-1.5 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors"
          >
            {state.isAnalyzing ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Play className="w-3.5 h-3.5" />
            )}
            {state.isAnalyzing ? 'Analyzing...' : 'Analyze Project'}
          </button>

          <button
            onClick={handleExplainSystem}
            disabled={!state.graph}
            className="flex items-center gap-1.5 bg-gray-700 hover:bg-gray-600 disabled:opacity-40 disabled:cursor-not-allowed text-gray-300 text-xs font-medium px-3 py-1.5 rounded-lg transition-colors"
          >
            <MessageSquare className="w-3.5 h-3.5" />
            Explain System
          </button>

          {/* Legend */}
          <div className="ml-auto flex items-center gap-3 text-[10px] text-gray-500">
            <LegendDot color="bg-red-500" label="Error" />
            <LegendDot color="bg-yellow-500" label="Warning" />
            <LegendDot color="bg-emerald-500" label="Healthy" />
          </div>
        </div>

        {/* Main content */}
        <div className="flex-1 flex overflow-hidden">
          {/* Map area */}
          <div className="flex-1 relative">
            {state.graph ? (
              <ReactFlowProvider>
                <NeuroMap graph={state.graph} />
              </ReactFlowProvider>
            ) : (
              <EmptyState isAnalyzing={state.isAnalyzing} onAnalyze={handleAnalyze} />
            )}
          </div>

          {/* Detail panel */}
          <div className="w-72 border-l border-gray-800 bg-gray-900/50 overflow-hidden">
            <DetailPanel />
          </div>
        </div>

        {/* Error toast */}
        <AnimatePresence>
          {state.error && (
            <motion.div
              initial={{ opacity: 0, y: 50 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 50 }}
              className="fixed bottom-4 left-1/2 -translate-x-1/2 bg-red-900/90 border border-red-500/50 text-red-200 text-xs px-4 py-2 rounded-xl shadow-xl max-w-sm text-center"
            >
              ⚠️ {state.error}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </AppContext.Provider>
  )
}

function LegendDot({ color, label }: { color: string; label: string }) {
  return (
    <div className="flex items-center gap-1">
      <div className={`w-2 h-2 rounded-full ${color}`} />
      <span>{label}</span>
    </div>
  )
}

function EmptyState({ isAnalyzing, onAnalyze }: { isAnalyzing: boolean; onAnalyze: () => void }) {
  return (
    <div className="h-full flex flex-col items-center justify-center gap-6 text-center p-8">
      <motion.div
        animate={{ y: [0, -8, 0] }}
        transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}
        className="text-7xl"
      >
        👻
      </motion.div>
      <div>
        <h2 className="text-gray-200 font-semibold text-xl mb-2">GhostDebugger</h2>
        <p className="text-gray-500 text-sm max-w-xs">
          Click "Analyze Project" to scan your codebase, detect bugs and visualize your system architecture.
        </p>
      </div>
      {!isAnalyzing && (
        <button
          onClick={onAnalyze}
          className="flex items-center gap-2 bg-purple-600 hover:bg-purple-500 text-white font-semibold px-6 py-2.5 rounded-xl transition-colors"
        >
          <Play className="w-4 h-4" />
          Start Analysis
        </button>
      )}
      {isAnalyzing && (
        <div className="flex items-center gap-2 text-blue-400">
          <Loader2 className="w-5 h-5 animate-spin" />
          <span>Analyzing your project...</span>
        </div>
      )}
    </div>
  )
}
