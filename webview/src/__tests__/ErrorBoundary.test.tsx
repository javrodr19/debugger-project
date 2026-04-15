import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { ErrorBoundary } from '../components/ErrorBoundary'

const Thrower = ({ shouldThrow }: { shouldThrow: boolean }) => {
  if (shouldThrow) {
    throw new Error('Test render error');
  }
  return <div>Success</div>;
};

describe('ErrorBoundary', () => {
  it('renders children when no error occurs', () => {
    render(
      <ErrorBoundary>
        <Thrower shouldThrow={false} />
      </ErrorBoundary>
    )
    expect(screen.getByText('Success')).toBeTruthy()
  })

  it('renders fallback UI when a child throws during render', () => {
    // Silence console.error for this test to keep output clean
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    
    render(
      <ErrorBoundary>
        <Thrower shouldThrow={true} />
      </ErrorBoundary>
    )

    expect(screen.getByText('Render Crash Detected')).toBeTruthy()
    expect(screen.getByText('Test render error')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Reload plugin/i })).toBeTruthy()
    
    consoleSpy.mockRestore();
  })
})
