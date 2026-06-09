import { useNavigate } from 'react-router-dom';

export default function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <div style={page}>
      <div className="page-enter" style={card}>
        <div style={code}>404</div>
        <h2 style={title}>Page not found</h2>
        <p style={sub}>The page you were looking for doesn't exist.</p>
        <button style={btn} onClick={() => navigate('/')}>Go to Timeline</button>
      </div>
    </div>
  );
}

const page:  React.CSSProperties = { minHeight: '80vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 32 };
const card:  React.CSSProperties = { textAlign: 'center', maxWidth: 380 };
const code:  React.CSSProperties = { fontSize: 80, fontWeight: 800, color: 'var(--border)', lineHeight: 1, marginBottom: 10 };
const title: React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)', marginBottom: 10 };
const sub:   React.CSSProperties = { color: 'var(--text-muted)', fontSize: 14, marginBottom: 28 };
const btn:   React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 24px', cursor: 'pointer', fontSize: 14 };
