import { memo, useState, useCallback } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { AnimatePresence, motion } from 'framer-motion'
import type { GraphNode, NodeStatus, FunctionInfo, VariableInfo } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'

// GitHub dark tokens
const C = {
  bg:       '#161b22',
  elevated: '#21262d',
  border:   '#30363d',
  text1:    '#e6edf3',
  text2:    '#8b949e',
  text3:    '#6e7681',
  errorText:'#f85149',
  errorBdr: '#6e2a2a',
  warnText: '#d29922',
  warnBdr:  '#5a4000',
  okText:   '#3fb950',
  okBdr:    '#1b4d2e',
  blueText: '#79c0ff',
}

const statusBorder: Record<NodeStatus, string> = {
  ERROR:   C.errorBdr,
  WARNING: C.warnBdr,
  HEALTHY: C.border,
}

const statusDot: Record<NodeStatus, string> = {
  ERROR:   C.errorText,
  WARNING: C.warnText,
  HEALTHY: C.okText,
}

const typeLabel: Record<string, string> = {
  FILE: 'FILE', FUNCTION: 'FN', CLASS: 'CLASS', COMPONENT: 'COMP',
  HOOK: 'HOOK', API_ROUTE: 'ROUTE', MODULE: 'MOD', SERVICE: 'SVC',
}

const typeColor: Record<string, string> = {
  FILE: C.text2, FUNCTION: '#d2a8ff', CLASS: '#79c0ff',
  COMPONENT: '#79c0ff', HOOK: '#d2a8ff', API_ROUTE: '#ffa657',
  MODULE: '#56d364', SERVICE: '#79c0ff',
}

interface CustomNodeData extends Record<string, unknown> {
  node: GraphNode
  isSelected: boolean
  isHighlighted: boolean
}

function CustomNodeComponent({ data }: NodeProps<CustomNodeData>) {
  const { node, isSelected, isHighlighted } = data
  const { state, dispatch } = useAppStore()
  const [expanded, setExpanded] = useState(false)

  const errorCount = node.issues.filter(i => i.severity === 'ERROR').length
  const warnCount  = node.issues.filter(i => i.severity === 'WARNING').length
  const hasSymbols = node.functions.length > 0 || node.variables.length > 0

  const bpKey = (line: number) => `${node.filePath}:${line}`
  const hasBp = (line: number) => state.breakpoints.includes(bpKey(line))

  const toggleBp = useCallback((line: number, e: React.MouseEvent) => {
    e.stopPropagation()
    const key = bpKey(line)
    if (state.breakpoints.includes(key)) {
      bridge.removeBreakpoint(node.filePath, line)
    } else {
      bridge.setBreakpoint(node.filePath, line)
    }
    dispatch({ type: 'TOGGLE_BREAKPOINT', payload: key })
  }, [state.breakpoints, node.filePath, dispatch])

  const borderColor = isHighlighted
    ? '#388bfd'
    : isSelected
    ? '#388bfd'
    : statusBorder[node.status]

  const outline = isSelected || isHighlighted
    ? `0 0 0 1px ${isHighlighted ? '#388bfd' : '#388bfd'}`
    : 'none'

  return (
    <div
      style={{
        background: C.bg,
        border: `1px solid ${borderColor}`,
        borderRadius: 8,
        minWidth: 160,
        maxWidth: expanded ? 240 : 200,
        boxShadow: outline,
        userSelect: 'none',
        cursor: 'pointer',
        overflow: 'hidden',
        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
      }}
    >
      <Handle
        type="target"
        position={Position.Left}
        style={{ background: C.text3, border: `1px solid ${C.border}`, width: 7, height: 7 }}
      />

      {/* Header */}
      <div style={{
        background: C.elevated,
        borderBottom: `1px solid ${C.border}`,
        padding: '5px 8px',
        display: 'flex',
        alignItems: 'center',
        gap: 5,
      }}>
        <span style={{ color: typeColor[node.type] ?? C.text2, fontSize: 8, fontWeight: 700, letterSpacing: '0.1em', textTransform: 'uppercase' }}>
          {typeLabel[node.type] ?? node.type}
        </span>
        <div style={{
          width: 5, height: 5, borderRadius: '50%',
          background: statusDot[node.status],
          flexShrink: 0,
        }} />
        <div style={{ flex: 1 }} />
        {errorCount > 0 && (
          <span style={{
            color: C.errorText, background: 'rgba(248,81,73,0.12)',
            border: '1px solid rgba(248,81,73,0.3)',
            fontSize: 8, fontWeight: 700,
            padding: '1px 4px', borderRadius: 3,
          }}>
            {errorCount}E
          </span>
        )}
        {warnCount > 0 && (
          <span style={{
            color: C.warnText, background: 'rgba(210,153,34,0.12)',
            border: '1px solid rgba(210,153,34,0.3)',
            fontSize: 8, fontWeight: 700,
            padding: '1px 4px', borderRadius: 3,
          }}>
            {warnCount}W
          </span>
        )}
      </div>

      {/* Body */}
      <div style={{ padding: '6px 8px' }}>
        <div
          style={{ color: C.text1, fontSize: 11, fontWeight: 600, marginBottom: 2 }}
          title={node.name}
        >
          {node.name.length > 22 ? node.name.slice(0, 20) + '…' : node.name}
        </div>
        <div style={{ color: C.text3, fontSize: 8, display: 'flex', gap: 6 }}>
          <span>L{node.lineStart}–{node.lineEnd}</span>
          {node.complexity > 5 && (
            <span style={{ color: node.complexity > 15 ? C.errorText : node.complexity > 10 ? C.warnText : C.text3 }}>
              cx:{node.complexity}
            </span>
          )}
        </div>

        {/* Complexity bar */}
        {node.complexity > 3 && (
          <div style={{
            height: 2, background: C.elevated, borderRadius: 1,
            overflow: 'hidden', marginTop: 5,
          }}>
            <div style={{
              height: '100%',
              width: `${Math.min((node.complexity / 20) * 100, 100)}%`,
              background: node.complexity > 15 ? C.errorText : node.complexity > 10 ? C.warnText : C.okText,
              borderRadius: 1,
            }} />
          </div>
        )}

        {/* Expand toggle */}
        {hasSymbols && (
          <button
            onMouseDown={e => e.stopPropagation()}
            onClick={e => { e.stopPropagation(); setExpanded(v => !v) }}
            style={{
              marginTop: 6, width: '100%', display: 'flex',
              alignItems: 'center', justifyContent: 'space-between',
              background: 'transparent', border: 'none', cursor: 'pointer',
              padding: '2px 0',
            }}
          >
            <span style={{ color: C.text3, fontSize: 8, letterSpacing: '0.06em' }}>
              {[
                node.functions.length > 0 && `${node.functions.length} fn`,
                node.variables.length > 0 && `${node.variables.length} var`,
              ].filter(Boolean).join('  ')}
            </span>
            <span style={{ color: C.text3, fontSize: 8 }}>{expanded ? '▴' : '▾'}</span>
          </button>
        )}
      </div>

      {/* Symbols panel */}
      <AnimatePresence>
        {expanded && hasSymbols && (
          <motion.div
            initial={{ height: 0 }}
            animate={{ height: 'auto' }}
            exit={{ height: 0 }}
            transition={{ duration: 0.12 }}
            style={{ overflow: 'hidden', borderTop: `1px solid ${C.border}` }}
          >
            <div style={{ maxHeight: 160, overflowY: 'auto', padding: '4px 0' }}>
              {node.functions.length > 0 && (
                <SymbolGroup label="Functions" color="#d2a8ff">
                  {node.functions.map(fn => (
                    <SymbolRow
                      key={`f${fn.line}`}
                      name={fn.name}
                      line={fn.line}
                      badge={fn.isAsync ? 'async' : 'fn'}
                      badgeColor="#d2a8ff"
                      hasBp={hasBp(fn.line)}
                      onToggle={e => toggleBp(fn.line, e)}
                    />
                  ))}
                </SymbolGroup>
              )}
              {node.variables.length > 0 && (
                <SymbolGroup label="Variables" color="#56d364">
                  {node.variables.map(v => (
                    <SymbolRow
                      key={`v${v.line}`}
                      name={v.name}
                      line={v.line}
                      badge={v.kind}
                      badgeColor="#56d364"
                      hasBp={hasBp(v.line)}
                      onToggle={e => toggleBp(v.line, e)}
                    />
                  ))}
                </SymbolGroup>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <Handle
        type="source"
        position={Position.Right}
        style={{ background: C.text3, border: `1px solid ${C.border}`, width: 7, height: 7 }}
      />
    </div>
  )
}

function SymbolGroup({ label, color, children }: { label: string; color: string; children: React.ReactNode }) {
  return (
    <div>
      <div style={{
        color, fontSize: 7, fontWeight: 700, textTransform: 'uppercase',
        letterSpacing: '0.1em', padding: '3px 8px 2px', opacity: 0.7,
      }}>
        {label}
      </div>
      {children}
    </div>
  )
}

function SymbolRow({
  name, line, badge, badgeColor, hasBp, onToggle,
}: {
  name: string; line: number; badge: string; badgeColor: string
  hasBp: boolean; onToggle: (e: React.MouseEvent) => void
}) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 5,
      padding: '2px 8px',
    }}
    onMouseEnter={e => (e.currentTarget as HTMLElement).style.background = C.elevated}
    onMouseLeave={e => (e.currentTarget as HTMLElement).style.background = 'transparent'}
    >
      {/* Breakpoint dot */}
      <button
        onMouseDown={e => e.stopPropagation()}
        onClick={onToggle}
        style={{
          width: 9, height: 9, borderRadius: '50%', flexShrink: 0,
          background: hasBp ? C.errorText : 'transparent',
          border: `1px solid ${hasBp ? C.errorText : C.border}`,
          cursor: 'pointer',
          transition: 'all 0.1s',
        }}
        title={hasBp ? 'Remove breakpoint' : 'Set breakpoint'}
      />
      <span style={{ color: badgeColor, fontSize: 7, fontWeight: 700, width: 28, flexShrink: 0, opacity: 0.8 }}>
        {badge}
      </span>
      <span style={{ color: C.text2, fontSize: 9, flex: 1, fontFamily: 'monospace' }}
        className="truncate" title={name}>
        {name}
      </span>
      <span style={{ color: C.text3, fontSize: 7, flexShrink: 0, fontFamily: 'monospace' }}>
        {line}
      </span>
    </div>
  )
}

export const CustomNode = memo(CustomNodeComponent)
