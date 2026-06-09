import { Component, ErrorInfo, ReactNode } from 'react';

interface Props  { children: ReactNode }
interface State  { error: Error | null }

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State { return { error }; }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div style={page}>
          <div style={card}>
            <div style={code}>500</div>
            <h2 style={title}>Something went wrong</h2>
            <p style={msg}>{this.state.error.message}</p>
            <button style={btn} onClick={() => this.setState({ error: null })}>Try again</button>
            <button style={{ ...btn, background: 'var(--surface2)', marginLeft: 10 }}
              onClick={() => window.location.href = '/'}>Go home</button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

const page:  React.CSSProperties = { minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 32 };
const card:  React.CSSProperties = { textAlign: 'center', maxWidth: 420 };
const code:  React.CSSProperties = { fontSize: 72, fontWeight: 800, color: 'var(--border)', lineHeight: 1, marginBottom: 12 };
const title: React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)', marginBottom: 10 };
const msg:   React.CSSProperties = { color: 'var(--text-muted)', fontSize: 14, marginBottom: 24, lineHeight: 1.6 };
const btn:   React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 20px', cursor: 'pointer', fontSize: 14 };
