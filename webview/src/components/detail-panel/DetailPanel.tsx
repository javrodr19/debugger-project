import React, { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  ChevronDown, ChevronRight, AlertCircle, AlertTriangle, Info,
  Eye, Wand2, Copy, Check, Loader2, FileCode, Activity, Layers
} from 'lucide-react'
import type { GraphNode, Issue, CodeFix, IssueSeverity, ProjectGraph, AnalysisMetrics, IssueSource } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'

// ─── Color tokens ─────────────────────────────────────────────────────────────
const C = {
  bg:        'var(--bg-base)',
  surface:   'var(--bg-surface)',
  elevated:  'var(--bg-elevated)',
  border:    'var(--border)',
  borderFg:  'var(--border-fg)',
  text1:     'var(--fg-primary)',
  text2:     'var(--fg-secondary)',
  text3:     'var(--fg-muted)',
  errorText: 'var(--error-text)',
  errorBg:   'var(--error-bg)',
  errorBdr:  'var(--error-border)',
  warnText:  'var(--warn-text)',
  warnBg:    'var(--warn-bg)',
  warnBdr:   'var(--warn-border)',
  okText:    'var(--ok-text)',
  okBg:      'var(--ok-bg)',
  okBdr:     'var(--ok-border)',
  blueText:  'var(--blue-text)',
  blueBg:    'var(--blue-bg)',
  blueBdr:   'var(--blue-border)',
  accent:    'var(--accent)',
}

// ─── Main component ───────────────────────────────────────────────────────────

export function DetailPanel() {
  const { state, dispatch } = useAppStore()
  const { selectedNode, selectedIssue, systemExplanation, graph, metrics } = state

  const [openSections, setOpenSections] = useState<Set<string>>(new Set(['info']))

  // Auto-open info section when node changes
  useEffect(() => {
    if (selectedNode) setOpenSections(new Set(['info']))
  }, [selectedNode?.id])

  const toggle = (id: string) => {
    setOpenSections(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  // No node selected → show Overview panel
  if (!selectedNode) {
    return (
      <OverviewPanel
        graph={graph}
        metrics={metrics}
        systemExplanation={systemExplanation}
        dispatch={dispatch}
      />
    )
  }

  return (
    <div className="h-full overflow-y-auto" style={{ background: C.bg, color: C.text1 }}>

      {selectedNode && (
        <>
          {/* ── Section 1: Node Info ── */}
          <Section
            id="info"
            title={selectedNode.name}
            subtitle="Node Details"
            isOpen={openSections.has('info')}
            onToggle={toggle}
            badge={null}
          >
            <NodeInfoContent node={selectedNode} />
          </Section>

          {/* ── Section 2: Issues ── */}
          <Section
            id="issues"
            title="Issues"
            subtitle={`${selectedNode.issues.length} detected`}
            isOpen={openSections.has('issues')}
            onToggle={toggle}
            badge={selectedNode.issues.length > 0 ? {
              count: selectedNode.issues.length,
              color: selectedNode.issues.some(i => i.severity === 'ERROR') ? C.errorText : C.warnText,
              bg: selectedNode.issues.some(i => i.severity === 'ERROR') ? C.errorBg : C.warnBg,
            } : null}
          >
            <IssuesContent
              issues={selectedNode.issues}
              selectedIssue={selectedIssue}
              explanations={state.explanations}
              onSelectIssue={(issue) => {
                dispatch({ type: 'SELECT_ISSUE', payload: issue })
                if (!openSections.has('solutions')) {
                  setOpenSections(prev => new Set([...prev, 'solutions']))
                }
              }}
            />
          </Section>

          {/* ── Section 3: Suggest Solutions ── */}
          <Section
            id="solutions"
            title="Suggest Solution"
            subtitle={selectedIssue ? `for: ${selectedIssue.title}` : 'Select an issue first'}
            isOpen={openSections.has('solutions')}
            onToggle={toggle}
            badge={null}
          >
            <SolutionsContent
              selectedNode={selectedNode}
              selectedIssue={selectedIssue}
              fixes={state.pendingFixes}
              loadingFix={state.loadingFix}
              applyingFix={state.applyingFix}
              dispatch={dispatch}
            />
          </Section>
        </>
      )}
    </div>
  )
}

// ─── Accordion Section ────────────────────────────────────────────────────────

function Section({
  id, title, subtitle, isOpen, onToggle, badge, children
}: {
  id: string
  title: string
  subtitle: string
  isOpen: boolean
  onToggle: (id: string) => void
  badge: { count: number; color: string; bg: string } | null
  children: React.ReactNode
}) {
  return (
    <div style={{
      borderBottom: `1px solid ${C.border}`,
      borderLeft: isOpen ? `2px solid ${C.accent}` : '2px solid transparent',
    }}>
      <button
        onClick={() => onToggle(id)}
        className="w-full flex items-center gap-2 text-left"
        style={{
          background: isOpen ? C.surface : 'transparent',
          borderRadius: 0,
          border: 'none',
          padding: '8px 12px',
          cursor: 'pointer',
          width: '100%',
          transition: 'background 0.12s',
        }}
        onMouseEnter={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.background = C.elevated }}
        onMouseLeave={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.background = 'transparent' }}
      >
        <span style={{ color: isOpen ? C.accent : C.text3, flexShrink: 0, transition: 'color 0.12s' }}>
          {isOpen
            ? <ChevronDown size={11} />
            : <ChevronRight size={11} />
          }
        </span>
        <div className="flex-1 min-w-0">
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{
              color: isOpen ? C.text1 : C.text2,
              fontSize: 10,
              fontWeight: 700,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              transition: 'color 0.12s',
            }} className="truncate">
              {title}
            </span>
            {badge && (
              <span
                style={{
                  color: badge.color,
                  background: badge.bg,
                  border: `1px solid ${badge.color}22`,
                  fontSize: 9,
                  fontWeight: 700,
                  padding: '1px 5px',
                  borderRadius: 0,
                  flexShrink: 0,
                  letterSpacing: '0.04em',
                }}
              >
                {badge.count}
              </span>
            )}
          </div>
          <span style={{ color: C.text3, fontSize: 9, display: 'block', marginTop: 2 }} className="truncate">
            {subtitle}
          </span>
        </div>
      </button>

      <AnimatePresence initial={false}>
        {isOpen && (
          <motion.div
            key="content"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.15, ease: 'easeInOut' }}
            style={{ overflow: 'hidden' }}
          >
            {children}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

// ─── Section 1: Node Info ─────────────────────────────────────────────────────

function NodeInfoContent({ node }: { node: GraphNode }) {
  return (
    <div style={{ background: C.bg, padding: '10px 12px 12px' }}>
      {/* Metrics row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6, marginBottom: 10 }}>
        <MetricBox label="Lines" value={String(node.lineEnd - node.lineStart + 1)} />
        <MetricBox
          label="Complexity"
          value={String(node.complexity)}
          color={node.complexity > 15 ? C.errorText : node.complexity > 10 ? C.warnText : undefined}
        />
        <MetricBox label="Issues" value={String(node.issues.length)}
          color={node.issues.length > 0 ? C.warnText : C.okText}
        />
      </div>

      {/* Type */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
        <span style={{ color: C.text3, fontSize: 9, textTransform: 'uppercase', letterSpacing: '0.08em' }}>Type</span>
        <span style={{
          color: C.blueText,
          background: C.blueBg,
          border: `1px solid ${C.blueBdr}`,
          fontSize: 9,
          padding: '1px 6px',
          borderRadius: 0,
          fontWeight: 600,
          letterSpacing: '0.06em',
        }}>
          {node.type}
        </span>
        <StatusPill status={node.status} />
      </div>

      {/* File path */}
      <div
        style={{
          background: C.elevated,
          border: `1px solid ${C.border}`,
          borderRadius: 0,
          padding: '5px 8px',
          marginBottom: 10,
        }}
      >
        <span style={{ color: C.text3, fontSize: 9 }}>
          {node.filePath.replace(/\\/g, '/').split('/').slice(-4).join('/')}
        </span>
      </div>

      {/* Complexity bar */}
      {node.complexity > 1 && (
        <div style={{ marginBottom: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
            <span style={{ color: C.text3, fontSize: 9 }}>Cyclomatic complexity</span>
            <span style={{ color: C.text3, fontSize: 9 }}>{node.complexity}/20</span>
          </div>
          <div style={{ height: 3, background: C.elevated, borderRadius: 2, overflow: 'hidden' }}>
            <div style={{
              height: '100%',
              width: `${Math.min((node.complexity / 20) * 100, 100)}%`,
              background: node.complexity > 15 ? C.errorText : node.complexity > 10 ? C.warnText : C.okText,
              borderRadius: 2,
            }} />
          </div>
        </div>
      )}

      {/* Analyze Impact button */}
      <button
        onClick={() => bridge.requestImpact(node.id)}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 6,
          background: C.elevated,
          border: `1px solid ${C.border}`,
          color: C.text2,
          fontSize: 10,
          fontWeight: 600,
          padding: '6px 0',
          borderRadius: 0,
          cursor: 'pointer',
          letterSpacing: '0.04em',
        }}
        onMouseEnter={e => {
          (e.currentTarget as HTMLElement).style.borderColor = C.accent
          ;(e.currentTarget as HTMLElement).style.color = C.blueText
        }}
        onMouseLeave={e => {
          (e.currentTarget as HTMLElement).style.borderColor = C.border
          ;(e.currentTarget as HTMLElement).style.color = C.text2
        }}
      >
        <Eye size={11} />
        Analyze Impact
      </button>
    </div>
  )
}

// ─── Section 2: Issues ────────────────────────────────────────────────────────

function IssuesContent({
  issues,
  selectedIssue,
  explanations,
  onSelectIssue,
}: {
  issues: Issue[]
  selectedIssue: Issue | null
  explanations: Record<string, string>
  onSelectIssue: (i: Issue) => void
}) {
  if (issues.length === 0) {
    return (
      <div style={{ padding: '12px', background: C.bg }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 6,
          background: C.okBg, border: `1px solid ${C.okBdr}`,
          borderRadius: 0, padding: '7px 10px',
        }}>
          <span style={{ color: C.okText, fontSize: 10 }}>No issues detected in this module</span>
        </div>
      </div>
    )
  }

  const sorted = [...issues].sort((a, b) => {
    const order = { ERROR: 0, WARNING: 1, INFO: 2 }
    return order[a.severity] - order[b.severity]
  })

  return (
    <div style={{ background: C.bg, padding: '8px 12px 12px' }}>
      {sorted.map((issue, idx) => (
        <IssueRow
          key={issue.id}
          issue={issue}
          isSelected={selectedIssue?.id === issue.id}
          explanation={explanations[issue.id]}
          onSelect={onSelectIssue}
          isLast={idx === sorted.length - 1}
        />
      ))}
    </div>
  )
}

function IssueRow({
  issue, isSelected, explanation, onSelect, isLast
}: {
  issue: Issue
  isSelected: boolean
  explanation?: string
  onSelect: (i: Issue) => void
  isLast: boolean
}) {
  const { color, bg, bdr } = severityStyle(issue.severity)

  return (
    <div
      style={{
        border: `1px solid ${isSelected ? bdr : C.border}`,
        borderRadius: 0,
        marginBottom: isLast ? 0 : 6,
        background: isSelected ? bg : C.surface,
        cursor: 'pointer',
        overflow: 'hidden',
        transition: 'border-color 0.15s',
      }}
      onClick={() => onSelect(issue)}
      onMouseEnter={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.borderColor = C.borderFg }}
      onMouseLeave={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.borderColor = C.border }}
    >
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '8px 10px' }}>
        <SeverityIcon severity={issue.severity} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ color: C.text1, fontSize: 10, fontWeight: 600, lineHeight: 1.4 }}>
            {issue.title}
          </div>
          <div style={{ color: C.text2, fontSize: 9, marginTop: 2, lineHeight: 1.5 }} className="line-clamp-2">
            {issue.description}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', marginTop: 4 }}>
            {issue.line > 0 && (
              <span style={{
                color: C.text3, background: C.elevated,
                border: `1px solid ${C.border}`,
                fontSize: 8, padding: '1px 5px', borderRadius: 0,
                fontFamily: 'var(--font-code)',
              }}>
                L{issue.line}
              </span>
            )}
            {issue.sources && issue.sources.length > 0 && (
              <span style={{ display: 'inline-flex', gap: 3, marginLeft: 4, flexShrink: 0 }}>
                {issue.sources.map(src => (
                  <ProvenanceBadge key={src} source={src} />
                ))}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Expanded: code snippet + explanation */}
      <AnimatePresence>
        {isSelected && (
          <motion.div
            initial={{ height: 0 }}
            animate={{ height: 'auto' }}
            exit={{ height: 0 }}
            style={{ overflow: 'hidden' }}
          >
            {issue.codeSnippet && (
              <pre style={{
                margin: 0,
                padding: '8px 10px',
                background: C.elevated,
                borderTop: `1px solid ${C.border}`,
                color: C.text2,
                fontSize: 9,
                fontFamily: 'var(--font-code)',
                overflowX: 'auto',
                maxHeight: 100,
                lineHeight: 1.5,
              }}>
                {issue.codeSnippet}
              </pre>
            )}
            <div style={{
              padding: '8px 10px',
              borderTop: `1px solid ${C.border}`,
              background: C.bg,
            }}>
              {explanation ? (
                <>
                  <div style={{ color: C.text3, fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>
                    AI Explanation
                  </div>
                  <p style={{ color: C.text2, fontSize: 9, lineHeight: 1.6, margin: 0 }}>{explanation}</p>
                </>
              ) : (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: C.text3 }}>
                  <Loader2 size={10} className="animate-spin" />
                  <span style={{ fontSize: 9 }}>Fetching explanation...</span>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

// ─── Section 3: Solutions ─────────────────────────────────────────────────────

function SolutionsContent({
  selectedNode,
  selectedIssue,
  fixes,
  loadingFix,
  applyingFix,
  dispatch,
}: {
  selectedNode: GraphNode
  selectedIssue: Issue | null
  fixes: Record<string, CodeFix>
  loadingFix: string | null
  applyingFix: string | null
  dispatch: React.Dispatch<any>
}) {
  const [copied, setCopied] = useState(false)

  if (!selectedIssue) {
    return (
      <div style={{ padding: '12px', background: C.bg }}>
        <div style={{
          background: C.surface, border: `1px solid ${C.border}`,
          borderRadius: 0, padding: '10px 12px', textAlign: 'center',
        }}>
          <span style={{ color: C.text3, fontSize: 10 }}>
            Select an issue in section 2 to generate a fix suggestion
          </span>
        </div>
      </div>
    )
  }

  const fix = fixes[selectedIssue.id]
  const isLoading = loadingFix === selectedIssue.id

  const handleGenerateFix = () => {
    dispatch({ type: 'SET_LOADING_FIX', payload: selectedIssue.id })
    bridge.requestFix(selectedIssue.id, selectedNode.id)
  }

  const handleCopyForAI = () => {
    const lang = detectLang(selectedIssue.filePath)
    const prompt = buildAIPrompt(selectedIssue, fix!, lang)
    navigator.clipboard.writeText(prompt).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  const handleApplyFix = () => {
    if (!fix || !selectedIssue) return
    dispatch({ type: 'SET_APPLYING_FIX', payload: selectedIssue.id })
    bridge.applyFix(selectedIssue.id, fix.id)
  }

  return (
    <div style={{ background: C.bg, padding: '10px 12px 12px' }}>
      {/* Issue reference */}
      <div style={{
        background: C.surface, border: `1px solid ${C.border}`,
        borderRadius: 0, padding: '6px 10px', marginBottom: 10,
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        <SeverityIcon severity={selectedIssue.severity} />
        <span style={{ color: C.text2, fontSize: 9, flex: 1 }} className="truncate">
          {selectedIssue.title}
        </span>
        <span style={{ color: C.text3, fontSize: 8, fontFamily: 'var(--font-code)' }}>L{selectedIssue.line}</span>
      </div>

      {!fix && !isLoading && (
        <button
          onClick={handleGenerateFix}
          style={{
            width: '100%',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
            background: C.accent,
            color: 'var(--fg-primary)',
            fontSize: 10, fontWeight: 700,
            padding: '8px 0',
            borderRadius: 0,
            cursor: 'pointer',
            border: 'none',
            letterSpacing: '0.04em',
          }}
          onMouseEnter={e => (e.currentTarget as HTMLElement).style.opacity = '0.85'}
          onMouseLeave={e => (e.currentTarget as HTMLElement).style.opacity = '1'}
        >
          <Wand2 size={11} />
          Generate Fix Suggestion
        </button>
      )}

      {isLoading && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          padding: '12px', background: C.surface,
          border: `1px solid ${C.border}`, borderRadius: 0,
          color: C.text2, fontSize: 10,
        }}>
          <Loader2 size={12} className="animate-spin" style={{ color: C.accent }} />
          Asking AI for a fix...
        </div>
      )}

      {fix && (
        <div>
          {/* Explanation */}
          {fix.description && (
            <div style={{
              background: C.surface, border: `1px solid ${C.border}`,
              borderRadius: 0, padding: '8px 10px', marginBottom: 10,
            }}>
              <div style={{ color: C.text3, fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>
                What changed
              </div>
              <p style={{ color: C.text2, fontSize: 10, lineHeight: 1.6, margin: 0 }}>{fix.description}</p>
            </div>
          )}

          {/* Trust badge */}
          <div style={{ marginBottom: 10 }}>
            <TrustBadge isDeterministic={fix.isDeterministic} />
          </div>

          {/* Code diff */}
          <CodeDiff original={fix.originalCode} fixed={fix.fixedCode} filePath={fix.filePath} />

          {/* Apply Fix */}
          <button
            onClick={handleApplyFix}
            disabled={applyingFix === selectedIssue.id}
            style={{
              width: '100%',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
              background: applyingFix === selectedIssue.id ? 'var(--bg-elevated)' : 'var(--accent)',
              color: applyingFix === selectedIssue.id ? 'var(--fg-muted)' : 'var(--fg-primary)',
              fontSize: 10, fontWeight: 700,
              padding: '8px 0',
              borderRadius: 0,
              cursor: applyingFix === selectedIssue.id ? 'not-allowed' : 'pointer',
              border: 'none',
              letterSpacing: '0.04em',
              marginBottom: 6,
              transition: 'opacity 0.15s',
              marginTop: 10,
            }}
          >
            {applyingFix === selectedIssue.id
              ? <><Loader2 size={11} className="animate-spin" /> Applying…</>
              : <><Check size={11} /> Apply Fix</>
            }
          </button>

          {/* Actions */}
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              onClick={handleCopyForAI}
              style={{
                flex: 1,
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
                background: copied ? C.okBg : C.elevated,
                border: `1px solid ${copied ? C.okBdr : C.border}`,
                color: copied ? C.okText : C.text1,
                fontSize: 10, fontWeight: 600,
                padding: '7px 0',
                borderRadius: 0,
                cursor: 'pointer',
                transition: 'all 0.15s',
              }}
            >
              {copied ? <Check size={11} /> : <Copy size={11} />}
              {copied ? 'Copied!' : 'Copy prompt for AI'}
            </button>
            <button
              onClick={handleGenerateFix}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                background: C.elevated, border: `1px solid ${C.border}`,
                color: C.text3, fontSize: 9,
                padding: '7px 10px', borderRadius: 0, cursor: 'pointer',
              }}
              title="Regenerate"
            >
              ↻
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Code Diff viewer ─────────────────────────────────────────────────────────

function CodeDiff({ original, fixed, filePath }: { original: string; fixed: string; filePath: string }) {
  const lang = detectLang(filePath)
  return (
    <div style={{ border: `1px solid ${C.border}`, borderRadius: 0, overflow: 'hidden' }}>
      {/* Original */}
      <div>
        <div style={{
          background: 'rgba(224, 85, 85, 0.06)',
          borderBottom: `1px solid rgba(224, 85, 85, 0.15)`,
          padding: '4px 10px',
          display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{ color: C.errorText, fontSize: 9, fontWeight: 700 }}>− Original</span>
          <span style={{ color: C.text3, fontSize: 8, fontFamily: 'var(--font-code)', marginLeft: 'auto' }}>{lang}</span>
        </div>
        <pre style={{
          margin: 0, padding: '8px 10px',
          background: 'rgba(224, 85, 85, 0.04)',
          color: '#ffa198',
          fontSize: 9, fontFamily: 'var(--font-code)',
          overflowX: 'auto', maxHeight: 130,
          lineHeight: 1.55,
        }}>
          {original || '—'}
        </pre>
      </div>

      {/* Divider */}
      <div style={{ height: 1, background: C.border }} />

      {/* Fixed */}
      <div>
        <div style={{
          background: 'rgba(61, 181, 102, 0.06)',
          borderBottom: `1px solid rgba(61, 181, 102, 0.15)`,
          padding: '4px 10px',
          display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{ color: C.okText, fontSize: 9, fontWeight: 700 }}>+ Suggested Fix</span>
        </div>
        <pre style={{
          margin: 0, padding: '8px 10px',
          background: 'rgba(61, 181, 102, 0.04)',
          color: '#7ee787',
          fontSize: 9, fontFamily: 'var(--font-code)',
          overflowX: 'auto', maxHeight: 130,
          lineHeight: 1.55,
        }}>
          {fixed || '—'}
        </pre>
      </div>
    </div>
  )
}

// ─── Overview panel (empty state) ────────────────────────────────────────────

function OverviewPanel({
  graph, metrics, systemExplanation, dispatch,
}: {
  graph: ProjectGraph | null
  metrics: AnalysisMetrics | null
  systemExplanation: string | null
  dispatch: React.Dispatch<any>
}) {
  const [summaryOpen, setSummaryOpen] = useState(false)
  const [loadingSummary, setLoadingSummary] = useState(false)

  const handleGenerateSummary = useCallback(() => {
    setLoadingSummary(true)
    bridge.requestSystemExplanation()
  }, [])

  // Stop spinner when explanation arrives
  useEffect(() => {
    if (systemExplanation) setLoadingSummary(false)
  }, [systemExplanation])

  if (!graph || !metrics) {
    return (
      <div style={{
        height: '100%', display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center',
        padding: 24, textAlign: 'center', background: C.bg,
      }}>
        <div style={{
          width: 40, height: 40, borderRadius: 0,
          background: C.surface, border: `1px solid ${C.border}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 20, marginBottom: 12,
        }}>
          <svg viewBox="0 0 13 13" width="22" height="22" aria-hidden="true">
            <path d="M6.5 1 L11 3 V6.5 C11 9 8.75 11.3 6.5 12 C4.25 11.3 2 9 2 6.5 V3 Z"
                  fill="none" stroke="var(--fg-primary)" strokeWidth="1.1" strokeLinejoin="round"/>
            <circle cx="5.2" cy="6.2" r="0.9" fill="none" stroke="var(--fg-primary)" strokeWidth="0.9"/>
            <circle cx="7.8" cy="7.6" r="0.9" fill="none" stroke="var(--fg-primary)" strokeWidth="0.9"/>
            <line x1="5.9" y1="6.7" x2="7.1" y2="7.2" stroke="var(--fg-primary)" strokeWidth="0.9" strokeLinecap="round"/>
          </svg>
        </div>
        <p style={{ color: C.text1, fontSize: 12, fontWeight: 600, margin: '0 0 6px' }}>Aegis Debug</p>
        <p style={{ color: C.text3, fontSize: 10, lineHeight: 1.6, maxWidth: 180 }}>
          Run an analysis, then click a node to inspect its code, issues and get fix suggestions.
        </p>
      </div>
    )
  }

  const allIssues = graph.nodes.flatMap(n => n.issues)
  const errors = allIssues.filter(i => i.severity === 'ERROR').length
  const warnings = allIssues.filter(i => i.severity === 'WARNING').length
  const info = allIssues.length - errors - warnings
  const errorNodes = graph.nodes.filter(n => n.status === 'ERROR').length
  const warnNodes  = graph.nodes.filter(n => n.status === 'WARNING').length
  const okNodes    = graph.nodes.filter(n => n.status === 'HEALTHY').length
  const health = metrics.healthScore
  const hColor = health >= 80 ? C.okText : health >= 50 ? C.warnText : C.errorText

  const topFiles = [...graph.nodes]
    .filter(n => n.issues.length > 0)
    .sort((a, b) => b.issues.length - a.issues.length)
    .slice(0, 6)

  return (
    <div style={{ height: '100%', overflowY: 'auto', background: C.bg, padding: 12 }}>

      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        marginBottom: 12, paddingBottom: 10,
        borderBottom: `1px solid ${C.border}`,
      }}>
        <Layers size={12} style={{ color: C.text3 }} />
        <span style={{ color: C.text1, fontSize: 11, fontWeight: 700, letterSpacing: '0.04em' }}>
          Overview
        </span>
      </div>

      {/* Health */}
      <div style={{
        background: C.surface, border: `1px solid ${C.border}`,
        borderRadius: 0, padding: '10px 12px', marginBottom: 10,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Activity size={11} style={{ color: C.text3 }} />
            <span style={{ color: C.text2, fontSize: 9, textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700 }}>
              Project Health
            </span>
          </div>
          <span style={{ color: hColor, fontSize: 18, fontWeight: 800, fontFamily: 'var(--font-code)' }}>
            {health.toFixed(0)}%
          </span>
        </div>
        <div style={{ height: 4, background: C.elevated, borderRadius: 2, overflow: 'hidden' }}>
          <motion.div
            style={{ height: '100%', background: hColor, borderRadius: 2 }}
            initial={{ width: 0 }}
            animate={{ width: `${health}%` }}
            transition={{ duration: 0.8, ease: 'easeOut' }}
          />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6 }}>
          <span style={{ color: C.text3, fontSize: 8 }}>{graph.nodes.length} modules</span>
          <span style={{ color: C.text3, fontSize: 8 }}>{graph.edges.length} deps</span>
        </div>
      </div>

      {/* Issue counts */}
      <SummaryGroup title="Issue Summary">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 6 }}>
          <SummaryTile count={errors} label="Errors" color={C.errorText} bg={C.errorBg} bdr={C.errorBdr} />
          <SummaryTile count={warnings} label="Warnings" color={C.warnText} bg={C.warnBg} bdr={C.warnBdr} />
          <SummaryTile count={info} label="Info" color={C.blueText} bg={C.blueBg} bdr={C.blueBdr} />
        </div>
      </SummaryGroup>

      {/* Module status */}
      <SummaryGroup title="Module Status">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 6 }}>
          <SummaryTile count={errorNodes} label="Broken" color={C.errorText} bg={C.errorBg} bdr={C.errorBdr} />
          <SummaryTile count={warnNodes}  label="At Risk" color={C.warnText}  bg={C.warnBg}  bdr={C.warnBdr}  />
          <SummaryTile count={okNodes}    label="Healthy" color={C.okText}    bg={C.okBg}    bdr={C.okBdr}    />
        </div>
      </SummaryGroup>

      {/* Circular dependencies */}
      {(graph.metadata.cycles?.length ?? 0) > 0 && (
        <SummaryGroup title="Circular Dependencies">
          <div style={{
            background: 'var(--warn-bg)',
            border: '1px solid var(--warn-border)',
            borderRadius: 0,
            padding: '8px 10px',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <div>
              <div style={{ color: 'var(--warn-text)', fontSize: 16, fontWeight: 800, fontFamily: 'var(--font-code)' }}>
                {graph.metadata.cycles!.length}
              </div>
              <div style={{ color: 'var(--warn-text)', fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.07em', opacity: 0.75, marginTop: 2 }}>
                Cycles Detected
              </div>
            </div>
            <span style={{ fontSize: 20 }}>🔄</span>
          </div>
        </SummaryGroup>
      )}

      {/* Hotspots */}
      {topFiles.length > 0 && (
        <SummaryGroup title="Hotspots">
          {topFiles.map(node => {
            const e = node.issues.filter(i => i.severity === 'ERROR').length
            const w = node.issues.filter(i => i.severity === 'WARNING').length
            return (
              <div key={node.id} style={{
                display: 'flex', alignItems: 'center', gap: 8,
                padding: '5px 8px', borderRadius: 0, marginBottom: 3,
                background: C.surface, border: `1px solid ${C.border}`,
              }}>
                <div style={{
                  width: 6, height: 6, borderRadius: '50%', flexShrink: 0,
                  background: node.status === 'ERROR' ? C.errorText : node.status === 'WARNING' ? C.warnText : C.okText,
                }} />
                <span style={{ color: C.text2, fontSize: 9, fontFamily: 'var(--font-code)', flex: 1 }} className="truncate">
                  {node.name}
                </span>
                <div style={{ display: 'flex', gap: 3, flexShrink: 0 }}>
                  {e > 0 && <Badge text={`${e}E`} color={C.errorText} bg={C.errorBg} />}
                  {w > 0 && <Badge text={`${w}W`} color={C.warnText}  bg={C.warnBg}  />}
                </div>
              </div>
            )
          })}
        </SummaryGroup>
      )}

      {/* AI Summary accordion */}
      <div style={{ marginTop: 4 }}>
        <button
          onClick={() => setSummaryOpen(v => !v)}
          style={{
            width: '100%', display: 'flex', alignItems: 'center', gap: 6,
            background: 'transparent', border: 'none', cursor: 'pointer',
            padding: '7px 0', borderTop: `1px solid ${C.border}`,
          }}
        >
          <Wand2 size={11} style={{ color: C.text3, flexShrink: 0 }} />
          <span style={{ color: C.text2, fontSize: 9, textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700, flex: 1, textAlign: 'left' }}>
            AI Summary
          </span>
          {summaryOpen
            ? <ChevronDown size={11} style={{ color: C.text3 }} />
            : <ChevronRight size={11} style={{ color: C.text3 }} />
          }
        </button>

        <AnimatePresence initial={false}>
          {summaryOpen && (
            <motion.div
              key="ai-summary"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18 }}
              style={{ overflow: 'hidden' }}
            >
              <div style={{ paddingBottom: 8 }}>
                {!systemExplanation && !loadingSummary && (
                  <button
                    onClick={handleGenerateSummary}
                    style={{
                      width: '100%', display: 'flex', alignItems: 'center',
                      justifyContent: 'center', gap: 6,
                      background: C.elevated, border: `1px solid ${C.border}`,
                      color: C.text1, fontSize: 10, fontWeight: 600,
                      padding: '8px 0', borderRadius: 0, cursor: 'pointer',
                    }}
                  >
                    <Wand2 size={11} />
                    Generate AI Summary
                  </button>
                )}

                {loadingSummary && (
                  <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                    padding: '10px', background: C.surface,
                    border: `1px solid ${C.border}`, borderRadius: 0,
                    color: C.text2, fontSize: 10,
                  }}>
                    <Loader2 size={12} className="animate-spin" style={{ color: C.accent }} />
                    Analyzing project...
                  </div>
                )}

                {systemExplanation && (
                  <div>
                    <div style={{
                      background: C.surface, border: `1px solid ${C.border}`,
                      borderRadius: 0, padding: '8px 10px', marginBottom: 6,
                    }}>
                      <p style={{ color: C.text2, fontSize: 10, lineHeight: 1.65, margin: 0 }}>
                        {systemExplanation}
                      </p>
                    </div>
                    <button
                      onClick={handleGenerateSummary}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
                        background: 'transparent', border: `1px solid ${C.border}`,
                        color: C.text3, fontSize: 9,
                        padding: '5px 10px', borderRadius: 0, cursor: 'pointer',
                        width: '100%',
                      }}
                    >
                      ↻ Refresh
                    </button>
                  </div>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  )
}

// ─── Small helpers ────────────────────────────────────────────────────────────

function SummaryGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ color: C.text3, fontSize: 9, textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700, marginBottom: 6 }}>
        {title}
      </div>
      {children}
    </div>
  )
}

function SummaryTile({ count, label, color, bg, bdr }: { count: number; label: string; color: string; bg: string; bdr: string }) {
  return (
    <div style={{
      background: bg, border: `1px solid ${bdr}`, borderRadius: 0,
      padding: '8px 6px', textAlign: 'center',
    }}>
      <div style={{ color, fontSize: 18, fontWeight: 800, fontFamily: 'var(--font-code)', lineHeight: 1 }}>{count}</div>
      <div style={{ color, fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.07em', marginTop: 3, opacity: 0.75 }}>{label}</div>
    </div>
  )
}

function MetricBox({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={{
      background: C.surface, border: `1px solid ${C.border}`, borderRadius: 0,
      padding: '6px 8px', textAlign: 'center',
    }}>
      <div style={{ color: color ?? C.text1, fontSize: 14, fontWeight: 700, fontFamily: 'var(--font-code)', lineHeight: 1 }}>{value}</div>
      <div style={{ color: C.text3, fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.07em', marginTop: 3 }}>{label}</div>
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  const map = {
    ERROR:   { color: C.errorText, bg: C.errorBg, bdr: C.errorBdr, label: 'Error' },
    WARNING: { color: C.warnText,  bg: C.warnBg,  bdr: C.warnBdr,  label: 'Warning' },
    HEALTHY: { color: C.okText,    bg: C.okBg,    bdr: C.okBdr,    label: 'Healthy' },
  } as Record<string, { color: string; bg: string; bdr: string; label: string }>
  const s = map[status] ?? map.HEALTHY
  return (
    <span style={{
      color: s.color, background: s.bg, border: `1px solid ${s.bdr}`,
      fontSize: 9, padding: '1px 6px', borderRadius: 0, fontWeight: 600,
    }}>
      {s.label}
    </span>
  )
}

function SeverityIcon({ severity }: { severity: IssueSeverity }) {
  if (severity === 'ERROR')   return <AlertCircle size={12} style={{ color: C.errorText, flexShrink: 0, marginTop: 1 }} />
  if (severity === 'WARNING') return <AlertTriangle size={12} style={{ color: C.warnText, flexShrink: 0, marginTop: 1 }} />
  return <Info size={12} style={{ color: C.blueText, flexShrink: 0, marginTop: 1 }} />
}

function Badge({ text, color, bg }: { text: string; color: string; bg: string }) {
  return (
    <span style={{
      color, background: bg,
      fontSize: 8, fontWeight: 700, padding: '1px 4px', borderRadius: 0,
    }}>
      {text}
    </span>
  )
}

function severityStyle(s: IssueSeverity) {
  if (s === 'ERROR')   return { color: C.errorText, bg: C.errorBg, bdr: C.errorBdr }
  if (s === 'WARNING') return { color: C.warnText,  bg: C.warnBg,  bdr: C.warnBdr  }
  return                      { color: C.blueText,  bg: C.blueBg,  bdr: C.blueBdr  }
}

function detectLang(filePath: string): string {
  if (filePath.endsWith('.tsx') || filePath.endsWith('.ts'))   return 'typescript'
  if (filePath.endsWith('.jsx') || filePath.endsWith('.js'))   return 'javascript'
  if (filePath.endsWith('.kt'))  return 'kotlin'
  if (filePath.endsWith('.java')) return 'java'
  if (filePath.endsWith('.py'))  return 'python'
  return 'code'
}

function buildAIPrompt(issue: Issue, fix: CodeFix, lang: string): string {
  return `# Fix Request — ${issue.title}

## Context
- **File**: \`${issue.filePath.replace(/\\/g, '/')}\`
- **Issue type**: ${issue.type}
- **Severity**: ${issue.severity}
- **Line**: ${issue.line}

## Problem description
${issue.description}
${fix.description ? `\nAI analysis: ${fix.description}` : ''}

## Current code (with bug)
\`\`\`${lang}
${fix.originalCode}
\`\`\`

## Suggested fix
\`\`\`${lang}
${fix.fixedCode}
\`\`\`

## Instructions
Review the suggested fix and apply it to the original code.
Make sure the fix:
1. Is compatible with the rest of the codebase
2. Follows the existing code style and naming conventions
3. Does not break any existing functionality
4. Handles edge cases properly

Respond with the final corrected code and a brief explanation of any additional improvements you made.`
}

function ProvenanceBadge({ source }: { source: IssueSource }) {
  let label = 'AI'
  let textColor = 'var(--badge-ai-text)'
  let bgColor = 'var(--badge-ai-bg)'

  if (source === 'STATIC') {
    label = 'STATIC'
    textColor = 'var(--badge-static-text)'
    bgColor = 'var(--badge-static-bg)'
  } else if (source === 'AI_CLOUD') {
    label = 'CLOUD'
    textColor = '#C084FC' // Purple
    bgColor = 'rgba(192, 132, 252, 0.15)'
  } else if (source === 'AI_LOCAL') {
    label = 'LOCAL'
    textColor = '#3DB566' // Green
    bgColor = 'rgba(61, 181, 102, 0.15)'
  }

  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center',
      color: textColor,
      background: bgColor,
      fontSize: 8, fontWeight: 700,
      padding: '1px 5px', borderRadius: 0,
      letterSpacing: '0.06em',
      flexShrink: 0,
    }}>
      {label}
    </span>
  )
}

function TrustBadge({ isDeterministic }: { isDeterministic?: boolean }) {
  const det = isDeterministic === true
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      color:      det ? 'var(--ok-text)'       : 'var(--badge-ai-text)',
      background: det ? 'var(--ok-bg)'         : 'var(--badge-ai-bg)',
      border: `1px solid ${det ? 'var(--ok-border)' : 'rgba(192,132,252,0.28)'}`,
      fontSize: 8, fontWeight: 700,
      padding: '2px 7px', borderRadius: 0,
      letterSpacing: '0.05em',
    }}>
      {det ? 'Deterministic' : 'AI-Generated'}
    </span>
  )
}
