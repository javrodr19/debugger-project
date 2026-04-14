import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
// EngineStatusPill must be exported from StatusBar.tsx for testing
import { EngineStatusPill } from '../components/layout/StatusBar'
import type { EngineStatusPayload } from '../types'

const make = (status: any, provider = 'OPENAI'): EngineStatusPayload =>
  ({ provider, status, message: undefined, latencyMs: undefined } as unknown as EngineStatusPayload)

describe('EngineStatusPill', () => {
  it('shows provider name when ONLINE', () => {
    render(<EngineStatusPill payload={make('ONLINE')} />)
    expect(screen.getByText('OpenAI · Online')).toBeTruthy()
  })

  it('shows Static Mode when FALLBACK_TO_STATIC', () => {
    render(<EngineStatusPill payload={make('FALLBACK_TO_STATIC', 'STATIC')} />)
    expect(screen.getByText('Static Mode')).toBeTruthy()
  })

  it('shows Static Only when DISABLED', () => {
    render(<EngineStatusPill payload={make('DISABLED', 'STATIC')} />)
    expect(screen.getByText('Static Only')).toBeTruthy()
  })

  it('shows Offline when OFFLINE', () => {
    render(<EngineStatusPill payload={make('OFFLINE', 'STATIC')} />)
    expect(screen.getByText('Offline')).toBeTruthy()
  })

  it('shows Degraded when DEGRADED', () => {
    render(<EngineStatusPill payload={make('DEGRADED')} />)
    expect(screen.getByText('Degraded')).toBeTruthy()
  })
})
