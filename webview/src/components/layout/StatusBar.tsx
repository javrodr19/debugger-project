import { motion } from 'framer-motion'
import { Activity, AlertCircle, AlertTriangle, CheckCircle, Loader2 } from 'lucide-react'
import type { AnalysisMetrics } from '../../types'

interface StatusBarProps {
  isAnalyzing: boolean
  metrics: AnalysisMetrics | null
  projectName?: string
  totalNodes?: number
}

export function StatusBar({ isAnalyzing, metrics, projectName, totalNodes }: StatusBarProps) {
  return (
    <div className="flex items-center gap-4 px-4 py-2 bg-gray-900/80 border-b border-gray-800 text-xs">
      {/* Logo */}
      <div className="flex items-center gap-1.5">
        <span className="text-base">👻</span>
        <span className="text-gray-300 font-semibold">GhostDebugger</span>
      </div>

      <div className="h-4 w-px bg-gray-700" />

      {/* Project name */}
      {projectName && (
        <>
          <span className="text-gray-400">{projectName}</span>
          <div className="h-4 w-px bg-gray-700" />
        </>
      )}

      {/* Analysis status */}
      {isAnalyzing ? (
        <div className="flex items-center gap-1.5 text-blue-400">
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
          <span>Analyzing...</span>
        </div>
      ) : metrics ? (
        <div className="flex items-center gap-3">
          {metrics.errorCount > 0 && (
            <div className="flex items-center gap-1 text-red-400">
              <AlertCircle className="w-3 h-3" />
              <span>{metrics.errorCount} errors</span>
            </div>
          )}
          {metrics.warningCount > 0 && (
            <div className="flex items-center gap-1 text-yellow-400">
              <AlertTriangle className="w-3 h-3" />
              <span>{metrics.warningCount} warnings</span>
            </div>
          )}
          {metrics.errorCount === 0 && metrics.warningCount === 0 && (
            <div className="flex items-center gap-1 text-emerald-400">
              <CheckCircle className="w-3 h-3" />
              <span>All good</span>
            </div>
          )}
        </div>
      ) : null}

      {/* Health score */}
      {metrics && (
        <div className="flex items-center gap-1.5 ml-auto">
          <Activity className="w-3.5 h-3.5 text-gray-500" />
          <span className="text-gray-400">Health:</span>
          <motion.span
            key={metrics.healthScore}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className={
              metrics.healthScore >= 80 ? 'text-emerald-400 font-semibold' :
              metrics.healthScore >= 50 ? 'text-yellow-400 font-semibold' :
              'text-red-400 font-semibold'
            }
          >
            {metrics.healthScore.toFixed(0)}%
          </motion.span>
        </div>
      )}

      {/* Node count */}
      {totalNodes !== undefined && totalNodes > 0 && (
        <div className="flex items-center gap-1 text-gray-500">
          <span>{totalNodes} modules</span>
        </div>
      )}
    </div>
  )
}
