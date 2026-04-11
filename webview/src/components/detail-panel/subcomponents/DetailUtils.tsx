import React from 'react'
import { AlertCircle, AlertTriangle, Info } from 'lucide-react'
import type { IssueSeverity } from '../../types'

export const C = {
  bg:        '#0d1117',
  surface:   '#161b22',
  elevated:  '#21262d',
  border:    '#30363d',
  borderFg:  '#484f58',
  text1:     '#e6edf3',
  text2:     '#8b949e',
  text3:     '#6e7681',
  errorText: '#f85149',
  errorBg:   'rgba(248,81,73,0.08)',
  errorBdr:  'rgba(248,81,73,0.25)',
  warnText:  '#d29922',
  warnBg:    'rgba(210,153,34,0.08)',
  warnBdr:   'rgba(210,153,34,0.25)',
  okText:    '#3fb950',
  okBg:      'rgba(63,185,80,0.08)',
  okBdr:     'rgba(63,185,80,0.25)',
  blueText:  '#79c0ff',
  blueBg:    'rgba(121,192,255,0.08)',
  blueBdr:   'rgba(121,192,255,0.2)',
  accent:    '#388bfd',
}

export function severityStyle(severity: IssueSeverity) {
  switch (severity) {
    case 'ERROR':   return { color: C.errorText, bg: C.errorBg, bdr: C.errorBdr }
    case 'WARNING': return { color: C.warnText,  bg: C.warnBg,  bdr: C.warnBdr  }
    default:        return { color: C.blueText,  bg: C.blueBg,  bdr: C.blueBdr  }
  }
}

export function SeverityIcon({ severity, size = 12 }: { severity: IssueSeverity; size?: number }) {
  switch (severity) {
    case 'ERROR':   return <AlertCircle size={size} style={{ color: C.errorText, flexShrink: 0 }} />
    case 'WARNING': return <AlertTriangle size={size} style={{ color: C.warnText, flexShrink: 0 }} />
    default:        return <Info size={size} style={{ color: C.blueText, flexShrink: 0 }} />
  }
}

export function detectLang(filePath: string) {
  const ext = filePath.split('.').pop()?.toLowerCase() || ''
  const mapping: Record<string, string> = {
    ts: 'TypeScript', tsx: 'TypeScript', js: 'JavaScript', jsx: 'JavaScript',
    kt: 'Kotlin', java: 'Java', py: 'Python', go: 'Go', rs: 'Rust',
    cs: 'C#', rb: 'Ruby', swift: 'Swift', php: 'PHP'
  }
  return mapping[ext] || 'Source Code'
}
