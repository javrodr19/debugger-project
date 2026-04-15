import React, { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      const errorMessage = this.state.error?.message || 'An unexpected error occurred';
      const truncatedMessage = errorMessage.length > 500 
        ? errorMessage.substring(0, 500) + '...' 
        : errorMessage;

      return (
        <div style={{
          height: '100%',
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--bg-base)',
          padding: '2rem'
        }}>
          <div style={{
            maxWidth: '600px',
            width: '100%',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--error-border)',
            padding: '1.5rem',
            borderRadius: '4px',
            display: 'flex',
            flexDirection: 'column',
            gap: '1rem'
          }}>
            <h2 style={{ 
              margin: 0, 
              color: 'var(--error-text)', 
              fontSize: '1.2rem',
              fontWeight: 600 
            }}>
              Render Crash Detected
            </h2>
            <p style={{ 
              margin: 0, 
              color: 'var(--fg-secondary)', 
              fontSize: '0.9rem',
              lineHeight: 1.5,
              fontFamily: 'var(--font-code)',
              wordBreak: 'break-word',
              background: 'rgba(0,0,0,0.2)',
              padding: '0.75rem',
              borderRadius: '2px'
            }}>
              {truncatedMessage}
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                onClick={this.handleReload}
                style={{
                  background: 'var(--accent)',
                  color: 'white',
                  border: 'none',
                  padding: '0.5rem 1rem',
                  borderRadius: '2px',
                  cursor: 'pointer',
                  fontSize: '0.85rem',
                  fontWeight: 500
                }}
              >
                Reload plugin
              </button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
