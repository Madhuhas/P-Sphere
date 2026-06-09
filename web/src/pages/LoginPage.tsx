import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Auth, setAuthToken } from '../services/api';

export default function LoginPage() {
  const [email,    setEmail]    = useState('');
  const [password, setPassword] = useState('');
  const [error,    setError]    = useState('');
  const [loading,  setLoading]  = useState(false);
  const navigate = useNavigate();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      const res = await Auth.login(email, password);
      setAuthToken(res.data.token);
      navigate('/');
    } catch {
      setError('Invalid email or password.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={page}>
      <div className="page-enter" style={card}>
        <h1 style={h1}>P‑Sphere</h1>
        <p style={sub}>Sign in to your personal cloud</p>
        <form onSubmit={submit} style={form}>
          <input style={input} type="email"    placeholder="Email"    value={email}    onChange={(e) => setEmail(e.target.value)}    required />
          <input style={input} type="password" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          {error && <p style={{ color: 'var(--danger)', fontSize: 13 }}>{error}</p>}
          <button style={{ ...btn, opacity: loading ? .6 : 1 }} type="submit" disabled={loading}>
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}

const page: React.CSSProperties = { minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg)' };
const card: React.CSSProperties = { background: 'var(--surface)', borderRadius: 16, padding: 40, width: 360, boxShadow: '0 8px 32px rgba(0,0,0,.3)' };
const h1:   React.CSSProperties = { fontSize: 28, fontWeight: 700, color: 'var(--accent)', marginBottom: 6 };
const sub:  React.CSSProperties = { color: 'var(--text-muted)', fontSize: 14, marginBottom: 28 };
const form: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 12 };
const input:React.CSSProperties = { background: 'var(--input-bg)', border: '1px solid var(--border)', borderRadius: 8, padding: '12px 14px', color: 'var(--text)', fontSize: 14 };
const btn:  React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '12px', fontSize: 15, cursor: 'pointer', marginTop: 4, transition: 'opacity .15s' };
