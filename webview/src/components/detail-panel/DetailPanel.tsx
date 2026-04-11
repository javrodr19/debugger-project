import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { AlertCircle, AlertTriangle, CheckCircle, Zap, Eye, MessageSquare, X } from 'lucide-react'
import type { GraphNode, Issue, CodeFix, IssueSeverity } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'

const severityIcon = {
  ERROR: <AlertCircle className="w-4 h-4 text-red-400" />,
  WARNING: <AlertTriangle className="w-4 h-4 text-yellow-400" />,
  INFO: <CheckCircle className="w-4 h-4 text-blue-400" />,
}

const severityBg: Record<IssueSeverity, string> = {
  ERROR: 'bg-red-500/10 border-red-500/30',
  WARNING: 'bg-yellow-500/10 border-yellow-500/30',
  INFO: 'bg-blue-500/10 border-blue-500/30',
}

export function DetailPanel() {
  const { state, dispatch } = useAppStore()
  const [whatIfQuestion, setWhatIfQuestion] = useState('')
  const [showWhatIf, setShowWhatIf] = useState(false)

  const { selectedNode, selectedIssue, pendingFix, systemExplanation } = state

  if (!selectedNode && !systemExplanation) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-center p-8">
        <div className="text-5xl mb-4">👻</div>
        <h3 className="text-gray-300 font-semibold text-lg mb-2">NeuroMap</h3>
        <p className="text-gray-500 text-sm max-w-[200px]">
          Click on a node in the graph to see details and AI-powered explanations.
        </p>
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto flex flex-col gap-4 p-4 text-sm">
      {/* System explanation panel */}
      <AnimatePresence>
        {systemExplanation && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="bg-purple-900/30 border border-purple-500/30 rounded-xl p-4"
          >
            <div className="flex items-center justify-between mb-2">
              <h4 className="text-purple-300 font-semibold flex items-center gap-1.5">
                <MessageSquare className="w-4 h-4" />
                System Analysis
              </h4>
              <button
                onClick={() => dispatch({ type: 'SET_SYSTEM_EXPLANATION', payload: '' })}
                className="text-gray-500 hover:text-gray-300 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-gray-300 text-xs leading-relaxed whitespace-pre-wrap">
              {systemExplanation}
            </p>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Node info */}
      {selectedNode && (
        <motion.div
          key={selectedNode.id}
          initial={{ opacity: 0, x: 10 }}
          animate={{ opacity: 1, x: 0 }}
          className="bg-gray-800/50 border border-gray-700 rounded-xl p-4"
        >
          <div className="flex items-center gap-2 mb-3">
            <StatusDot status={selectedNode.status} />
            <h3 className="text-white font-semibold truncate">{selectedNode.name}</h3>
          </div>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <Metric label="Type" value={selectedNode.type} />
            <Metric label="Complexity" value={String(selectedNode.complexity)} />
            <Metric label="Issues" value={String(selectedNode.issues.length)} />
            <Metric label="Lines" value={`${selectedNode.lineEnd - selectedNode.lineStart + 1}`} />
          </div>
          <p className="text-gray-500 text-[10px] mt-2 truncate" title={selectedNode.filePath}>
            {selectedNode.filePath.split('/').slice(-2).join('/')}
          </p>

          {/* Impact button */}
          <button
            onClick={() => bridge.requestImpact(selectedNode.id)}
            className="mt-3 w-full flex items-center justify-center gap-1.5 bg-purple-500/10 hover:bg-purple-500/20 border border-purple-500/30 text-purple-300 text-xs font-medium py-1.5 rounded-lg transition-colors"
          >
            <Eye className="w-3.5 h-3.5" />
            Impact Analysis
          </button>
        </motion.div>
      )}

      {/* Issues list */}
      {selectedNode && selectedNode.issues.length > 0 && (
        <div className="flex flex-col gap-2">
          <h4 className="text-gray-400 text-xs font-semibold uppercase tracking-wider">
            Issues ({selectedNode.issues.length})
          </h4>
          {selectedNode.issues.map(issue => (
            <IssueCard
              key={issue.id}
              issue={issue}
              isSelected={selectedIssue?.id === issue.id}
              explanation={state.explanations[issue.id]}
              pendingFix={pendingFix?.issueId === issue.id ? pendingFix : null}
              onSelect={() => dispatch({ type: 'SELECT_ISSUE', payload: issue })}
              onFix={() => bridge.requestFix(issue.id, selectedNode.id)}
            />
          ))}
        </div>
      )}

      {/* What-if chat */}
      {selectedNode && (
        <div className="mt-auto">
          {!showWhatIf ? (
            <button
              onClick={() => setShowWhatIf(true)}
              className="w-full flex items-center justify-center gap-1.5 bg-gray-800 hover:bg-gray-700 border border-gray-700 text-gray-400 text-xs py-2 rounded-lg transition-colors"
            >
              <Zap className="w-3.5 h-3.5" />
              Ask What-If
            </button>
          ) : (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-gray-800/50 border border-gray-700 rounded-xl p-3"
            >
              <h4 className="text-gray-300 text-xs font-semibold mb-2 flex items-center gap-1.5">
                <Zap className="w-3.5 h-3.5 text-yellow-400" />
                CTO Mode
              </h4>
              <textarea
                value={whatIfQuestion}
                onChange={e => setWhatIfQuestion(e.target.value)}
                placeholder="¿Qué pasa si esto escala a 10.000 usuarios?"
                className="w-full bg-gray-900 border border-gray-700 rounded-lg text-gray-300 text-xs p-2 resize-none h-16 placeholder-gray-600 focus:outline-none focus:border-purple-500"
              />
              <div className="flex gap-2 mt-2">
                <button
                  onClick={() => {
                    if (whatIfQuestion.trim()) {
                      bridge.askWhatIf(whatIfQuestion)
                      setWhatIfQuestion('')
                      setShowWhatIf(false)
                    }
                  }}
                  className="flex-1 bg-purple-600 hover:bg-purple-500 text-white text-xs font-medium py-1.5 rounded-lg transition-colors"
                >
                  Ask
                </button>
                <button
                  onClick={() => setShowWhatIf(false)}
                  className="px-3 bg-gray-700 hover:bg-gray-600 text-gray-400 text-xs rounded-lg transition-colors"
                >
                  Cancel
                </button>
              </div>
            </motion.div>
          )}
        </div>
      )}
    </div>
  )
}

function IssueCard({
  issue,
  isSelected,
  explanation,
  pendingFix,
  onSelect,
  onFix,
}: {
  issue: Issue
  isSelected: boolean
  explanation?: string
  pendingFix: CodeFix | null
  onSelect: () => void
  onFix: () => void
}) {
  return (
    <motion.div
      layout
      className={`border rounded-xl overflow-hidden cursor-pointer transition-colors ${severityBg[issue.severity]}
        ${isSelected ? 'ring-1 ring-purple-400/50' : ''}`}
      onClick={onSelect}
    >
      <div className="flex items-start gap-2 p-3">
        <div className="flex-shrink-0 mt-0.5">{severityIcon[issue.severity]}</div>
        <div className="flex-1 min-w-0">
          <p className="text-gray-200 text-xs font-medium leading-snug">{issue.title}</p>
          <p className="text-gray-400 text-[10px] mt-0.5 line-clamp-2">{issue.description}</p>
        </div>
      </div>

      <AnimatePresence>
        {isSelected && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            className="border-t border-gray-700/50"
          >
            {/* Code snippet */}
            {issue.codeSnippet && (
              <pre className="text-[10px] text-gray-400 bg-gray-900/50 p-3 overflow-x-auto font-mono leading-relaxed max-h-24">
                {issue.codeSnippet}
              </pre>
            )}

            {/* AI Explanation */}
            {explanation ? (
              <div className="p-3 bg-purple-900/20">
                <p className="text-[10px] text-gray-400 font-semibold uppercase tracking-wider mb-1">
                  🧠 AI Explanation
                </p>
                <p className="text-gray-300 text-xs leading-relaxed">{explanation}</p>
              </div>
            ) : (
              <div className="p-3">
                <p className="text-gray-500 text-[10px] italic">Loading AI explanation...</p>
              </div>
            )}

            {/* Fix suggestion */}
            {pendingFix && (
              <div className="p-3 bg-emerald-900/20 border-t border-gray-700/50">
                <p className="text-[10px] text-gray-400 font-semibold uppercase tracking-wider mb-1">
                  🔧 Suggested Fix
                </p>
                <p className="text-gray-300 text-xs mb-2">{pendingFix.description}</p>
                <pre className="text-[10px] text-emerald-300 bg-emerald-900/30 p-2 rounded font-mono leading-relaxed max-h-32 overflow-auto">
                  {pendingFix.fixedCode}
                </pre>
              </div>
            )}

            {/* Fix button */}
            {issue.severity === 'ERROR' || issue.severity === 'WARNING' ? (
              <div className="p-3 pt-2">
                <button
                  onClick={(e) => { e.stopPropagation(); onFix() }}
                  className="w-full flex items-center justify-center gap-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold py-2 rounded-lg transition-colors"
                >
                  ✨ Fix it
                </button>
              </div>
            ) : null}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  )
}

function StatusDot({ status }: { status: string }) {
  const color = status === 'ERROR' ? 'bg-red-500' : status === 'WARNING' ? 'bg-yellow-500' : 'bg-emerald-500'
  return (
    <motion.div
      className={`w-2.5 h-2.5 rounded-full flex-shrink-0 ${color}`}
      animate={status === 'ERROR' ? { scale: [1, 1.3, 1] } : {}}
      transition={{ duration: 1.5, repeat: Infinity }}
    />
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-gray-900/50 rounded-lg px-2.5 py-1.5">
      <p className="text-gray-500 text-[9px] uppercase tracking-wide">{label}</p>
      <p className="text-gray-200 text-xs font-medium">{value}</p>
    </div>
  )
}
