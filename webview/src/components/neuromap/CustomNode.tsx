import { memo, useState, useCallback, useEffect } from 'react'
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react'
import { AnimatePresence, motion } from 'framer-motion'
import type { GraphNode, NodeStatus, DebugVariable, Issue } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'

// ... (C constants remain same)
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
  cyanText: '#56d4dd',
}

const statusBorder: Record<string, string> = {
  ERROR:   C.errorBdr,
  WARNING: C.warnBdr,
  HEALTHY: C.border,
}

const statusDot: Record<string, string> = {
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

export type CustomNodeData = {
  node: GraphNode
  isSelected: boolean
  isHighlighted: boolean
  isDebugActive: boolean
}

type CustomNodeProps = NodeProps<Node<CustomNodeData, 'custom'>>

function CustomNodeComponent({ data }: CustomNodeProps) {
  const { node, isSelected, isHighlighted, isDebugActive } = data
  const { state, dispatch } = useAppStore()
  const [expanded, setExpanded] = useState(false)

  const errorCount = node.issues.filter((i: Issue) => i.severity === 'ERROR').length
  const warnCount  = node.issues.filter((i: Issue) => i.severity === 'WARNING').length
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

  // When expanded, set this node as focused so it gets high z-index
  useEffect(() => {
    if (expanded) {
      dispatch({ type: 'SET_FOCUSED_NODE', payload: node.id })
    } else {
      // Only clear if we're the currently focused node
      if (state.focusedNodeId === node.id) {
        dispatch({ type: 'SET_FOCUSED_NODE', payload: null })
      }
    }
  }, [expanded, node.id, dispatch, state.focusedNodeId])

  const borderColor = isDebugActive
    ? C.cyanText
    : isHighlighted
    ? '#388bfd'
    : isSelected
    ? '#388bfd'
    : (statusBorder[node.status] || C.border)

  const outline = isDebugActive
    ? `0 0 0 2px ${C.cyanText}, 0 0 12px rgba(86,212,221,0.4)`
    : isSelected || isHighlighted
    ? `0 0 0 1px #388bfd`
    : 'none'

  // Debug variables from frame — cross-reference with node's symbols
  const debugVars = isDebugActive ? state.debugFrame?.variables ?? [] : []

  // Build a map of variable name → debug value for quick lookup
  const debugValueMap = new Map<string, DebugVariable>()
  debugVars.forEach((v: DebugVariable) => debugValueMap.set(v.name, v))

  return (
    <div
      style={{
        background: C.bg,
        border: `1px solid ${borderColor}`,
        borderRadius: 0,
        minWidth: 160,
        maxWidth: expanded ? 280 : 200,
        boxShadow: outline,
        userSelect: 'none',
        cursor: 'pointer',
        overflow: 'hidden',
        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
        position: 'relative',
        transition: 'width 0.2s ease, max-width 0.2s ease',
      }}
    >
      <Handle
        type="target"
        position={Position.Left}
        style={{ background: C.text3, border: `1px solid ${C.border}`, width: 7, height: 7 }}
      />

      {/* Debug active pulse */}
      {isDebugActive && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, height: 2,
          background: `linear-gradient(90deg, transparent, ${C.cyanText}, transparent)`,
          animation: 'debug-scan 1.5s ease-in-out infinite',
        }} />
      )}

      {/* Header */}
      <div style={{
        background: isDebugActive ? 'rgba(86,212,221,0.08)' : C.elevated,
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
          background: isDebugActive ? C.cyanText : (statusDot[node.status] || C.okText),
          flexShrink: 0,
          animation: isDebugActive ? 'pulse-glow-cyan 1s ease-in-out infinite' : undefined,
        }} />
        <div style={{ flex: 1 }} />
        {isDebugActive && (
          <span style={{
            color: C.cyanText, background: 'rgba(86,212,221,0.12)',
            border: '1px solid rgba(86,212,221,0.3)',
            fontSize: 7, fontWeight: 700,
            padding: '1px 4px', borderRadius: 0,
          }}>
            DEBUG
          </span>
        )}
        {errorCount > 0 && (
          <span style={{
            color: C.errorText, background: 'rgba(248,81,73,0.12)',
            border: '1px solid rgba(248,81,73,0.3)',
            fontSize: 8, fontWeight: 700,
            padding: '1px 4px', borderRadius: 0,
          }}>
            {errorCount}E
          </span>
        )}
        {warnCount > 0 && (
          <span style={{
            color: C.warnText, background: 'rgba(210,153,34,0.12)',
            border: '1px solid rgba(210,153,34,0.3)',
            fontSize: 8, fontWeight: 700,
            padding: '1px 4px', borderRadius: 0,
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
            onClick={e => { e.stopPropagation(); setExpanded((v: boolean) => !v) }}
            style={{
              marginTop: 6, width: '100%', display: 'flex',
              alignItems: 'center', justifyContent: 'space-between',
              background: expanded ? 'rgba(56,139,253,0.08)' : 'transparent',
              border: expanded ? '1px solid rgba(56,139,253,0.2)' : '1px solid transparent',
              borderRadius: 0,
              cursor: 'pointer',
              padding: '3px 4px',
              transition: 'all 0.1s',
            }}
          >
            <span style={{ color: expanded ? C.blueText : C.text3, fontSize: 8, letterSpacing: '0.06em' }}>
              {[
                node.functions.length > 0 && `${node.functions.length} fn`,
                node.variables.length > 0 && `${node.variables.length} var`,
              ].filter(Boolean).join('  ')}
            </span>
            <span style={{ color: expanded ? C.blueText : C.text3, fontSize: 8 }}>{expanded ? '▴' : '▾'}</span>
          </button>
        )}
      </div>

      {/* Symbols panel — shows function/variable names + debug values */}
      <AnimatePresence>
        {expanded && hasSymbols && (
          <motion.div
            initial={{ height: 0 }}
            animate={{ height: 'auto' }}
            exit={{ height: 0 }}
            transition={{ duration: 0.12 }}
            style={{ overflow: 'hidden', borderTop: `1px solid ${C.border}` }}
          >
            <div style={{ maxHeight: 200, overflowY: 'auto', padding: '4px 0' }}>
              {node.functions.length > 0 && (
                <SymbolGroup label="Functions" color="#d2a8ff">
                  {node.functions.map((fn: any) => {
                    const debugVal = debugValueMap.get(fn.name)
                    return (
                      <SymbolRow
                        key={`f${fn.line}`}
                        name={fn.name}
                        line={fn.line}
                        badge={fn.isAsync ? 'async' : 'fn'}
                        badgeColor="#d2a8ff"
                        hasBp={hasBp(fn.line)}
                        onToggle={(e: React.MouseEvent) => toggleBp(fn.line, e)}
                        debugValue={debugVal?.value}
                        debugType={debugVal?.type}
                      />
                    )
                  })}
                </SymbolGroup>
              )}
              {node.variables.length > 0 && (
                <SymbolGroup label="Variables" color="#56d364">
                  {node.variables.map((v: any) => {
                    const debugVal = debugValueMap.get(v.name)
                    return (
                      <SymbolRow
                        key={`v${v.line}`}
                        name={v.name}
                        line={v.line}
                        badge={v.kind}
                        badgeColor="#56d364"
                        hasBp={hasBp(v.line)}
                        onToggle={(e: React.MouseEvent) => toggleBp(v.line, e)}
                        debugValue={debugVal?.value}
                        debugType={debugVal?.type}
                      />
                    )
                  })}
                </SymbolGroup>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Debug Variables Overlay (when debug-active but NOT expanded) */}
      <AnimatePresence>
        {isDebugActive && !expanded && debugVars.length > 0 && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            style={{
              borderTop: `1px solid ${C.cyanText}`,
              background: 'rgba(86,212,221,0.05)',
              overflow: 'hidden',
            }}
          >
            <div style={{ padding: '4px 8px 6px' }}>
              <div style={{ color: C.cyanText, fontSize: 7, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 3 }}>
                Variables
              </div>
              {debugVars.slice(0, 6).map((v: DebugVariable, i: number) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '1px 0', fontSize: 8 }}>
                  <span style={{ color: C.cyanText, fontFamily: 'monospace', minWidth: 50 }}>{v.name}</span>
                  <span style={{ color: C.text3 }}>=</span>
                  <span style={{ color: C.text1, fontFamily: 'monospace', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.value}</span>
                  {v.type && <span style={{ color: C.text3, fontSize: 7 }}>{v.type}</span>}
                </div>
              ))}
              {debugVars.length > 6 && (
                <div style={{ color: C.text3, fontSize: 7, marginTop: 2 }}>+{debugVars.length - 6} more</div>
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
  name, line, badge, badgeColor, hasBp, onToggle, debugValue, debugType,
}: {
  name: string; line: number; badge: string; badgeColor: string
  hasBp: boolean; onToggle: (e: React.MouseEvent) => void
  debugValue?: string; debugType?: string
}) {
  return (
    <div
      style={{
        display: 'flex', alignItems: 'center', gap: 5,
        padding: '2px 8px',
        flexWrap: debugValue ? 'wrap' : undefined,
      }}
      onMouseEnter={(e: React.MouseEvent) => (e.currentTarget as HTMLElement).style.background = C.elevated}
      onMouseLeave={(e: React.MouseEvent) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
    >
      {/* Breakpoint dot */}
      <button
        onMouseDown={(e: React.MouseEvent) => e.stopPropagation()}
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
      <span style={{ color: C.text2, fontSize: 9, flex: 1, fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
        title={name}>
        {name}
      </span>
      <span style={{ color: C.text3, fontSize: 7, flexShrink: 0, fontFamily: 'monospace' }}>
        {line}
      </span>

      {/* Debug value — shown inline when available */}
      {debugValue && (
        <div style={{
          width: '100%',
          paddingLeft: 42,
          display: 'flex',
          alignItems: 'center',
          gap: 4,
          marginTop: 1,
        }}>
          <span style={{ color: C.cyanText, fontSize: 7 }}>=</span>
          <span style={{
            color: '#7ee787',
            fontSize: 8,
            fontFamily: 'monospace',
            background: 'rgba(63,185,80,0.08)',
            border: '1px solid rgba(63,185,80,0.15)',
            padding: '0 4px',
            borderRadius: 0,
            maxWidth: 140,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
            title={debugValue}
          >
            {debugValue}
          </span>
          {debugType && (
            <span style={{ color: C.text3, fontSize: 6 }}>{debugType}</span>
          )}
        </div>
      )}
    </div>
  )
}

export const CustomNode = memo(CustomNodeComponent)
