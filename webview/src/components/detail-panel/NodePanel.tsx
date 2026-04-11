import React, { useState, useEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Eye, Wand2, Copy, Check, Loader2 } from 'lucide-react'
import type { GraphNode, Issue, CodeFix } from '../../types'
import { bridge } from '../../bridge/pluginBridge'
import { C, SeverityIcon, detectLang } from './subcomponents/DetailUtils'
import { Section, MetricBox, StatusPill } from './subcomponents/DetailComponents'

interface NodePanelProps {
  selectedNode: GraphNode
  selectedIssue: Issue | null
  explanations: Record<string, string>
  pendingFixes: Record<string, CodeFix>
  loadingFix: string | null
  dispatch: React.Dispatch<any>
}

export function NodePanel({ selectedNode, selectedIssue, explanations, pendingFixes, loadingFix, dispatch }: NodePanelProps) {
  const [openSections, setOpenSections] = useState<Set<string>>(new Set(['info']))

  useEffect(() => {
    if (selectedNode) setOpenSections(new Set(['info']))
  }, [selectedNode.id])

  const toggle = (id: string) => {
    setOpenSections(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  return (
    <div className="h-full overflow-y-auto" style={{ background: C.bg, color: C.text1 }}>
      <Section
        id="info"
        title={selectedNode.name}
        subtitle="Node Details"
        isOpen={openSections.has('info')}
        onToggle={toggle}
        badge={null}
      >
        <div style={{ background: C.bg, padding: '10px 12px 12px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6, marginBottom: 10 }}>
            <MetricBox label="Lines" value={String(selectedNode.lineEnd - selectedNode.lineStart + 1)} />
            <MetricBox label="Complexity" value={String(selectedNode.complexity)} color={selectedNode.complexity > 15 ? C.errorText : selectedNode.complexity > 10 ? C.warnText : undefined} />
            <MetricBox label="Issues" value={String(selectedNode.issues.length)} color={selectedNode.issues.length > 0 ? C.warnText : C.okText} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
            <span style={{ color: C.text3, fontSize: 9, textTransform: 'uppercase' }}>Type</span>
            <span style={{ color: C.blueText, background: C.blueBg, border: `1px solid ${C.blueBdr}`, fontSize: 9, padding: '1px 6px', borderRadius: 4 }}>{selectedNode.type}</span>
            <StatusPill status={selectedNode.status} />
          </div>
          <button onClick={() => bridge.requestImpact(selectedNode.id)} style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, background: C.elevated, border: `1px solid ${C.border}`, color: C.text2, fontSize: 10, padding: '6px 0', borderRadius: 6 }}>
            <Eye size={11} /> Analyze Impact
          </button>
        </div>
      </Section>

      <Section
        id="issues"
        title="Issues"
        subtitle={`${selectedNode.issues.length} detected`}
        isOpen={openSections.has('issues')}
        onToggle={toggle}
        badge={selectedNode.issues.length > 0 ? { count: selectedNode.issues.length, color: C.warnText, bg: C.warnBg } : null}
      >
        <div style={{ background: C.bg, padding: '8px 12px' }}>
          {selectedNode.issues.map(issue => (
            <div key={issue.id} onClick={() => dispatch({ type: 'SELECT_ISSUE', payload: issue })} style={{ background: C.surface, border: `1px solid ${selectedIssue?.id === issue.id ? C.accent : C.border}`, borderRadius: 6, padding: '8px', marginBottom: 6, cursor: 'pointer' }}>
              <div style={{ display: 'flex', gap: 8 }}>
                <SeverityIcon severity={issue.severity} />
                <div style={{ fontSize: 10, fontWeight: 600 }}>{issue.title}</div>
              </div>
              {selectedIssue?.id === issue.id && (
                <div style={{ marginTop: 8, fontSize: 9, color: C.text2, background: C.bg, padding: 8, borderRadius: 4 }}>
                  {explanations[issue.id] || 'Fetching AI explanation...'}
                </div>
              )}
            </div>
          ))}
        </div>
      </Section>

      {selectedIssue && (
        <Section
          id="solutions"
          title="Suggest Solution"
          subtitle={`for: ${selectedIssue.title}`}
          isOpen={openSections.has('solutions')}
          onToggle={toggle}
          badge={null}
        >
          <div style={{ padding: 12, background: C.bg }}>
            {!pendingFixes[selectedIssue.id] && !loadingFix && (
              <button onClick={() => bridge.requestFix(selectedIssue.id, selectedNode.id)} style={{ width: '100%', padding: '8px', background: C.accent, color: '#fff', borderRadius: 6, fontSize: 10, fontWeight: 700 }}>
                <Wand2 size={11} inline style={{ marginRight: 6 }} /> Generate Fix
              </button>
            )}
            {loadingFix === selectedIssue.id && <div style={{ textAlign: 'center', fontSize: 10, color: C.text2 }}><Loader2 size={12} className="animate-spin" /> Thinking...</div>}
            {pendingFixes[selectedIssue.id] && (
               <div style={{ fontSize: 9, color: C.text2, background: C.surface, padding: 8, borderRadius: 6 }}>
                 <div style={{ fontWeight: 700, marginBottom: 4 }}>AI SUGGESTION:</div>
                 {pendingFixes[selectedIssue.id].description}
               </div>
            )}
          </div>
        </Section>
      )}
    </div>
  )
}
