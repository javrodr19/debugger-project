import type { AnalysisMetrics, EngineStatusPayload, AnalysisProgressPayload } from '../../types'
import { bridge } from '../../bridge/pluginBridge'

interface StatusBarProps {
  isAnalyzing: boolean
  analysisProgress: AnalysisProgressPayload | null
  metrics: AnalysisMetrics | null
  projectName?: string
  totalNodes?: number
  isAutoRefreshing?: boolean
  engineStatus?: EngineStatusPayload | null
}

export function StatusBar({ isAnalyzing, analysisProgress, metrics, projectName, totalNodes, isAutoRefreshing, engineStatus }: StatusBarProps) {
  const health = metrics?.healthScore ?? null
  const hColor = health == null ? 'var(--fg-muted)'
    : health >= 80 ? 'var(--ok-text)'
    : health >= 50 ? 'var(--warn-text)'
    : 'var(--error-text)'

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      height: 26,
      background: 'var(--bg-surface)',
      borderBottom: '1px solid var(--bg-elevated)',
      fontFamily: 'var(--font-code)',
      fontSize: 10,
      flexShrink: 0,
      overflow: 'hidden',
    }}>
      {/* Brand */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        padding: '0 12px', height: '100%',
        background: 'var(--bg-elevated)',
        borderRight: '1px solid var(--border)',
        flexShrink: 0,
      }}>
        <span style={{ color: 'var(--blue-text)', fontWeight: 700, letterSpacing: '0.06em' }}>
          Aegis Debug
        </span>
      </div>

      {/* Engine status pill */}
      {engineStatus && <EngineStatusPill payload={engineStatus} />}

      {/* Project name */}
      {projectName && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderRight: '1px solid var(--bg-elevated)',
          display: 'flex', alignItems: 'center',
          color: 'var(--fg-secondary)',
          flexShrink: 0,
        }}>
          {projectName}
        </div>
      )}

      {/* Status */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '0 12px', flex: 1 }}>
        {isAnalyzing ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: 'var(--accent)' }}>⟳ {analysisProgress ? analysisProgress.text : 'Analyzing…'}</span>
            {analysisProgress && (
              <span style={{ color: 'var(--fg-muted)', fontSize: 9 }}>
                {Math.round(analysisProgress.fraction * 100)}%
              </span>
            )}
          </div>
        ) : metrics ? (
          <>
            {metrics.errorCount > 0 && (
              <span style={{ color: 'var(--error-text)' }}>
                ✕ {metrics.errorCount} {metrics.errorCount === 1 ? 'error' : 'errors'}
              </span>
            )}
            {metrics.warningCount > 0 && (
              <span style={{ color: 'var(--warn-text)' }}>
                ⚠ {metrics.warningCount} {metrics.warningCount === 1 ? 'warning' : 'warnings'}
              </span>
            )}
            {metrics.errorCount === 0 && metrics.warningCount === 0 && (
              <span style={{ color: 'var(--ok-text)' }}>✓ No issues</span>
            )}
          </>
        ) : null}
      </div>

      {/* Cancel button */}
      {isAnalyzing && (
        <div style={{ padding: '0 8px', display: 'flex', alignItems: 'center', borderLeft: '1px solid var(--border)' }}>
          <button
            type="button"
            onClick={() => bridge.cancelAnalysis()}
            style={{
              fontSize: 9,
              padding: '2px 8px',
              border: '1px solid var(--border-fg)',
              background: 'transparent',
              color: 'var(--fg-secondary)',
              borderRadius: 3,
              cursor: 'pointer'
            }}
          >
            Cancel
          </button>
        </div>
      )}

      {/* Auto-refresh indicator */}

      {isAutoRefreshing && (
        <div style={{
          padding: '0 10px', height: '100%',
          borderLeft: '1px solid var(--bg-elevated)',
          display: 'flex', alignItems: 'center', gap: 5,
          flexShrink: 0,
        }}>
          <div style={{
            width: 5, height: 5, borderRadius: '50%',
            background: 'var(--accent)',
            animation: 'pulse-glow-blue 1.5s ease-in-out infinite',
          }} />
          <span style={{ color: 'var(--accent)', fontSize: 9 }}>Auto</span>
        </div>
      )}

      {/* Node count */}
      {totalNodes !== undefined && totalNodes > 0 && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderLeft: '1px solid var(--bg-elevated)',
          display: 'flex', alignItems: 'center',
          color: 'var(--fg-muted)',
          flexShrink: 0,
        }}>
          {totalNodes} modules
        </div>
      )}

      {/* Health */}
      {health !== null && (
        <div style={{
          padding: '0 12px', height: '100%',
          borderLeft: '1px solid var(--bg-elevated)',
          display: 'flex', alignItems: 'center', gap: 5,
          flexShrink: 0,
        }}>
          <span style={{ color: 'var(--fg-muted)' }}>health</span>
          <span style={{ color: hColor, fontWeight: 700 }}>{health.toFixed(0)}%</span>
        </div>
      )}
    </div>
  )
}

function providerLabel(provider: string): string {
  if (provider === 'OPENAI') return 'OpenAI'
  if (provider === 'OLLAMA') return 'Ollama'
  return 'Static'
}

export function EngineStatusPill({ payload }: { payload: EngineStatusPayload }) {
  let dotColor: string
  let label: string

  switch (payload.status) {
    case 'ONLINE':
      dotColor = 'var(--ok-text)'
      label    = `${providerLabel(payload.provider)} · Online`
      break
    case 'FALLBACK_TO_STATIC':
      dotColor = 'var(--warn-text)'
      label    = 'Static Mode'
      break
    case 'DEGRADED':
      dotColor = 'var(--warn-text)'
      label    = 'Degraded'
      break
    case 'OFFLINE':
      dotColor = 'var(--error-text)'
      label    = 'Offline'
      break
    case 'DISABLED':
    default:
      dotColor = 'var(--fg-muted)'
      label    = 'Static Only'
  }

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 5,
      padding: '0 10px', height: '100%',
      borderRight: '1px solid var(--border)',
      flexShrink: 0,
    }}>
      <div style={{
        width: 5, height: 5, borderRadius: '50%',
        background: dotColor, flexShrink: 0,
      }} />
      <span style={{ color: 'var(--fg-secondary)', fontSize: 9 }}>{label}</span>
    </div>
  )
}
