import React from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { C } from './DetailUtils'

export function MetricBox({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={{
      background: C.surface,
      border: `1px solid ${C.border}`,
      borderRadius: 6,
      padding: '6px 8px',
      display: 'flex',
      flexDirection: 'column',
    }}>
      <span style={{ color: C.text3, fontSize: 8, textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</span>
      <span style={{ color: color || C.text1, fontSize: 13, fontWeight: 700, fontFamily: 'monospace', marginTop: 1 }}>{value}</span>
    </div>
  )
}

export function StatusPill({ status }: { status: string }) {
  const isOk = status === 'HEALTHY'
  const isError = status === 'ERROR'
  return (
    <span style={{
      color: isOk ? C.okText : isError ? C.errorText : C.warnText,
      background: isOk ? C.okBg : isError ? C.errorBg : C.warnBg,
      border: `1px solid ${isOk ? C.okBdr : isError ? C.errorBdr : C.warnBdr}`,
      fontSize: 8,
      padding: '1px 5px',
      borderRadius: 3,
      fontWeight: 700,
    }}>
      {status}
    </span>
  )
}

export function Section({
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
    <div style={{ borderBottom: `1px solid ${C.border}` }}>
      <button
        onClick={() => onToggle(id)}
        className="w-full flex items-center gap-2 px-3 py-2.5 text-left transition-colors"
        style={{ background: isOpen ? C.surface : 'transparent' }}
      >
        <span style={{ color: C.text3, flexShrink: 0 }}>
          {isOpen ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        </span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span style={{ color: C.text1, fontSize: 11, fontWeight: 600 }} className="truncate">{title}</span>
            {badge && (
              <span style={{
                color: badge.color, background: badge.bg,
                fontSize: 9, fontWeight: 700, padding: '1px 5px', borderRadius: 4, flexShrink: 0,
              }}>{badge.count}</span>
            )}
          </div>
          <span style={{ color: C.text3, fontSize: 9, display: 'block', marginTop: 1 }} className="truncate">{subtitle}</span>
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

export function SummaryGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ color: C.text3, fontSize: 9, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
        <div style={{ height: 1, flex: 1, background: C.border }} />
        {title}
        <div style={{ height: 1, flex: 1, background: C.border }} />
      </div>
      {children}
    </div>
  )
}

export function SummaryTile({ count, label, color, bg, bdr }: { count: number; label: string; color: string; bg: string; bdr: string }) {
  return (
    <div style={{
      background: bg, border: `1px solid ${bdr}`, borderRadius: 8, padding: '10px 8px',
      display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
    }}>
      <span style={{ color, fontSize: 16, fontWeight: 800, fontFamily: 'monospace' }}>{count}</span>
      <span style={{ color: C.text3, fontSize: 8, fontWeight: 600, textTransform: 'uppercase' }}>{label}</span>
    </div>
  )
}
