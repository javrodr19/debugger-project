import type { AnalysisMetrics } from '../../types'

interface StatusBarProps {
  isAnalyzing: boolean
  metrics: AnalysisMetrics | null
  projectName?: string
  totalNodes?: number
  isAutoRefreshing?: boolean
}

export function StatusBar({ isAnalyzing, metrics, projectName, totalNodes, isAutoRefreshing }: StatusBarProps) {
  const health = metrics?.healthScore ?? null
  const hColor = health == null ? '#6e7681'
    : health >= 80 ? '#3fb950'
    : health >= 50 ? '#d29922'
    : '#f85149'

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      height: 26,
      background: '#161b22',
      borderBottom: '1px solid #21262d',
      fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
      fontSize: 10,
      flexShrink: 0,
      overflow: 'hidden',
    }}>
      {/* Brand */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        padding: '0 12px', height: '100%',
        background: '#21262d',
        borderRight: '1px solid #30363d',
        flexShrink: 0,
      }}>
        <span style={{ fontSize: 11 }}>👻</span>
        <span style={{ color: '#79c0ff', fontWeight: 700, letterSpacing: '0.06em' }}>
          GhostDebugger
        </span>
      </div>

      {/* Project name */}
      {projectName && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderRight: '1px solid #21262d',
          display: 'flex', alignItems: 'center',
          color: '#8b949e',
          flexShrink: 0,
        }}>
          {projectName}
        </div>
      )}

      {/* Status */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '0 12px' }}>
        {isAnalyzing ? (
          <span style={{ color: '#388bfd' }}>⟳ Analyzing…</span>
        ) : metrics ? (
          <>
            {metrics.errorCount > 0 && (
              <span style={{ color: '#f85149' }}>
                ✕ {metrics.errorCount} {metrics.errorCount === 1 ? 'error' : 'errors'}
              </span>
            )}
            {metrics.warningCount > 0 && (
              <span style={{ color: '#d29922' }}>
                ⚠ {metrics.warningCount} {metrics.warningCount === 1 ? 'warning' : 'warnings'}
              </span>
            )}
            {metrics.errorCount === 0 && metrics.warningCount === 0 && (
              <span style={{ color: '#3fb950' }}>✓ No issues</span>
            )}
          </>
        ) : null}
      </div>

      <div style={{ flex: 1 }} />

      {/* Auto-refresh indicator */}
      {isAutoRefreshing && (
        <div style={{
          padding: '0 10px', height: '100%',
          borderLeft: '1px solid #21262d',
          display: 'flex', alignItems: 'center', gap: 5,
          flexShrink: 0,
        }}>
          <div style={{
            width: 5, height: 5, borderRadius: '50%',
            background: '#388bfd',
            animation: 'pulse-glow-blue 1.5s ease-in-out infinite',
          }} />
          <span style={{ color: '#388bfd', fontSize: 9 }}>Auto</span>
        </div>
      )}

      {/* Node count */}
      {totalNodes !== undefined && totalNodes > 0 && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderLeft: '1px solid #21262d',
          display: 'flex', alignItems: 'center',
          color: '#6e7681',
          flexShrink: 0,
        }}>
          {totalNodes} modules
        </div>
      )}

      {/* Health */}
      {health !== null && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderLeft: '1px solid #21262d',
          display: 'flex', alignItems: 'center', gap: 5,
          flexShrink: 0,
        }}>
          <span style={{ color: '#6e7681' }}>health</span>
          <span style={{ color: hColor, fontWeight: 700 }}>{health.toFixed(0)}%</span>
        </div>
      )}
    </div>
  )
}
