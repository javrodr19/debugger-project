import { memo } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { motion } from 'framer-motion'
import type { GraphNode, NodeStatus } from '../../types'

const statusColors: Record<NodeStatus, { border: string; bg: string; glow: string; dot: string }> = {
  ERROR: {
    border: 'border-red-500',
    bg: 'bg-red-950/80',
    glow: '0 0 16px rgba(239,68,68,0.5)',
    dot: 'bg-red-500',
  },
  WARNING: {
    border: 'border-yellow-500',
    bg: 'bg-yellow-950/80',
    glow: '0 0 12px rgba(234,179,8,0.4)',
    dot: 'bg-yellow-500',
  },
  HEALTHY: {
    border: 'border-emerald-600',
    bg: 'bg-emerald-950/60',
    glow: '0 0 8px rgba(16,185,129,0.2)',
    dot: 'bg-emerald-500',
  },
}

const typeIcons: Record<string, string> = {
  FILE: '📄',
  FUNCTION: 'ƒ',
  CLASS: '⬡',
  COMPONENT: '⚛',
  HOOK: '🪝',
  API_ROUTE: '🛣',
  MODULE: '📦',
  SERVICE: '⚙',
}

interface CustomNodeData extends Record<string, unknown> {
  node: GraphNode
  isSelected: boolean
  isHighlighted: boolean
}

function CustomNodeComponent({ data }: NodeProps<CustomNodeData>) {
  const { node, isSelected, isHighlighted } = data
  const colors = statusColors[node.status]
  const icon = typeIcons[node.type] ?? '📄'
  const errorCount = node.issues.filter(i => i.severity === 'ERROR').length
  const warnCount = node.issues.filter(i => i.severity === 'WARNING').length

  return (
    <motion.div
      initial={{ scale: 0.8, opacity: 0 }}
      animate={{
        scale: isSelected ? 1.05 : 1,
        opacity: 1,
        boxShadow: isHighlighted ? '0 0 20px rgba(139,92,246,0.8)' : colors.glow,
      }}
      transition={{ type: 'spring', stiffness: 300, damping: 20 }}
      className={`
        relative min-w-[140px] max-w-[180px] rounded-xl border-2
        backdrop-blur-sm cursor-pointer select-none
        ${colors.border} ${colors.bg}
        ${isSelected ? 'ring-2 ring-purple-400 ring-offset-1 ring-offset-gray-900' : ''}
        ${isHighlighted ? 'ring-2 ring-purple-500' : ''}
      `}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="!w-2.5 !h-2.5 !bg-gray-600 !border-gray-500"
      />

      <div className="p-3">
        {/* Header */}
        <div className="flex items-center gap-2 mb-1.5">
          <span className="text-base">{icon}</span>
          <span className={`w-2 h-2 rounded-full flex-shrink-0 ${colors.dot}`} />
          <span className="text-gray-400 text-[10px] uppercase tracking-wider font-medium">
            {node.type}
          </span>
        </div>

        {/* Name */}
        <div className="text-white text-xs font-semibold truncate" title={node.name}>
          {node.name}
        </div>

        {/* Issue badges */}
        {(errorCount > 0 || warnCount > 0) && (
          <div className="flex gap-1.5 mt-2">
            {errorCount > 0 && (
              <span className="inline-flex items-center gap-0.5 bg-red-500/20 text-red-400 text-[10px] font-medium px-1.5 py-0.5 rounded-full border border-red-500/30">
                🔴 {errorCount}
              </span>
            )}
            {warnCount > 0 && (
              <span className="inline-flex items-center gap-0.5 bg-yellow-500/20 text-yellow-400 text-[10px] font-medium px-1.5 py-0.5 rounded-full border border-yellow-500/30">
                🟡 {warnCount}
              </span>
            )}
          </div>
        )}

        {/* Complexity meter */}
        {node.complexity > 5 && (
          <div className="mt-2">
            <div className="h-1 bg-gray-700 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${
                  node.complexity > 15 ? 'bg-red-500' :
                  node.complexity > 10 ? 'bg-yellow-500' : 'bg-emerald-500'
                }`}
                style={{ width: `${Math.min((node.complexity / 20) * 100, 100)}%` }}
              />
            </div>
          </div>
        )}
      </div>

      {/* Pulsing animation for errors */}
      {node.status === 'ERROR' && (
        <motion.div
          className="absolute inset-0 rounded-xl border-2 border-red-500 pointer-events-none"
          animate={{ opacity: [0.6, 0, 0.6] }}
          transition={{ duration: 2, repeat: Infinity, ease: 'easeInOut' }}
        />
      )}

      <Handle
        type="source"
        position={Position.Right}
        className="!w-2.5 !h-2.5 !bg-gray-600 !border-gray-500"
      />
    </motion.div>
  )
}

export const CustomNode = memo(CustomNodeComponent)
