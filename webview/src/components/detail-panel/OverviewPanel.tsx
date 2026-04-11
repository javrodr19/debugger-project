import React, { useState, useEffect, useCallback } from 'react'
import { motion } from 'framer-motion'
import { Layers, Activity, ChevronRight, AlertCircle } from 'lucide-react'
import type { ProjectGraph, AnalysisMetrics } from '../../types'
import { bridge } from '../../bridge/pluginBridge'
import { C } from './subcomponents/DetailUtils'
import { SummaryGroup, SummaryTile } from './subcomponents/DetailComponents'

interface OverviewPanelProps {
  graph: ProjectGraph | null
  metrics: AnalysisMetrics | null
  systemExplanation: string | null
  dispatch: React.Dispatch<any>
}

export function OverviewPanel({ graph, metrics, systemExplanation, dispatch }: OverviewPanelProps) {
  const [loadingSummary, setLoadingSummary] = useState(false)

  const handleGenerateSummary = useCallback(() => {
    setLoadingSummary(true)
    bridge.requestSystemExplanation()
  }, [])

  useEffect(() => {
    if (systemExplanation) setLoadingSummary(false)
  }, [systemExplanation])

  if (!graph || !metrics) {
    return (
      <div style={{ height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: 24, textAlign: 'center', background: C.bg }}>
        <div style={{ width: 40, height: 40, borderRadius: 10, background: C.surface, border: `1px solid ${C.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 20, marginBottom: 12 }}>👻</div>
        <p style={{ color: C.text1, fontSize: 12, fontWeight: 600, margin: '0 0 6px' }}>GhostDebugger</p>
        <p style={{ color: C.text3, fontSize: 10, lineHeight: 1.6, maxWidth: 180 }}>Run an analysis, then click a node to inspect its code, issues and get fix suggestions.</p>
      </div>
    )
  }

  const allIssues = graph.nodes.flatMap(n => n.issues)
  const errors = allIssues.filter(i => i.severity === 'ERROR').length
  const warnings = allIssues.filter(i => i.severity === 'WARNING').length
  const info = allIssues.length - errors - warnings
  const errorNodes = graph.nodes.filter(n => n.status === 'ERROR').length
  const warnNodes = graph.nodes.filter(n => n.status === 'WARNING').length
  const okNodes = graph.nodes.filter(n => n.status === 'HEALTHY').length
  const health = metrics.healthScore
  const hColor = health >= 80 ? C.okText : health >= 50 ? C.warnText : C.errorText

  return (
    <div style={{ height: '100%', overflowY: 'auto', background: C.bg, padding: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12, paddingBottom: 10, borderBottom: `1px solid ${C.border}` }}>
        <Layers size={12} style={{ color: C.text3 }} />
        <span style={{ color: C.text1, fontSize: 11, fontWeight: 700, letterSpacing: '0.04em' }}>Overview</span>
      </div>

      <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 8, padding: '10px 12px', marginBottom: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Activity size={11} style={{ color: C.text3 }} />
            <span style={{ color: C.text2, fontSize: 9, textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700 }}>Project Health</span>
          </div>
          <span style={{ color: hColor, fontSize: 18, fontWeight: 800, fontFamily: 'monospace' }}>{health.toFixed(0)}%</span>
        </div>
        <div style={{ height: 4, background: C.elevated, borderRadius: 2, overflow: 'hidden' }}>
          <motion.div style={{ height: '100%', background: hColor, borderRadius: 2 }} initial={{ width: 0 }} animate={{ width: `${health}%` }} transition={{ duration: 0.8 }} />
        </div>
      </div>

      <SummaryGroup title="Issue Summary">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 6 }}>
          <SummaryTile count={errors} label="Errors" color={C.errorText} bg={C.errorBg} bdr={C.errorBdr} />
          <SummaryTile count={warnings} label="Warnings" color={C.warnText} bg={C.warnBg} bdr={C.warnBdr} />
          <SummaryTile count={info} label="Info" color={C.blueText} bg={C.blueBg} bdr={C.blueBdr} />
        </div>
      </SummaryGroup>

      <SummaryGroup title="Module Status">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 6 }}>
          <SummaryTile count={errorNodes} label="Broken" color={C.errorText} bg={C.errorBg} bdr={C.errorBdr} />
          <SummaryTile count={warnNodes} label="At Risk" color={C.warnText} bg={C.warnBg} bdr={C.warnBdr} />
          <SummaryTile count={okNodes} label="Healthy" color={C.okText} bg={C.okBg} bdr={C.okBdr} />
        </div>
      </SummaryGroup>

      {(graph.metadata.cycles?.length ?? 0) > 0 && (
        <SummaryGroup title="Circular Dependencies">
          <div style={{ background: C.errorBg, border: `1px solid ${C.errorBdr}`, borderRadius: 6, padding: '8px 10px', display: 'flex', alignItems: 'center', gap: 8 }}>
            <AlertCircle size={14} style={{ color: C.errorText }} />
            <span style={{ color: C.errorText, fontSize: 10, fontWeight: 700 }}>{graph.metadata.cycles?.length} cycles detected</span>
          </div>
        </SummaryGroup>
      )}

      {/* AI Summary Button could go here */}
    </div>
  )
}
