import { useEffect, useState } from 'react';
import { api } from '../services/api';

type Status = 'online' | 'offline' | 'backend-down';

export default function OfflineBanner() {
  const [status, setStatus] = useState<Status>('online');

  useEffect(() => {
    let timer: ReturnType<typeof setInterval>;

    async function check() {
      if (!navigator.onLine) { setStatus('offline'); return; }
      try {
        await api.get('/api/health', { timeout: 3000 });
        setStatus('online');
      } catch {
        setStatus('backend-down');
      }
    }

    const handleOnline  = () => check();
    const handleOffline = () => setStatus('offline');

    window.addEventListener('online',  handleOnline);
    window.addEventListener('offline', handleOffline);
    timer = setInterval(check, 30_000);
    check();

    return () => {
      window.removeEventListener('online',  handleOnline);
      window.removeEventListener('offline', handleOffline);
      clearInterval(timer);
    };
  }, []);

  if (status === 'online') return null;

  const msg = status === 'offline'
    ? '⚡ No internet connection — some features are unavailable.'
    : '🔴 Cannot reach the P-Sphere backend. Is the core server running?';

  return (
    <div style={{
      position: 'sticky', top: 0, zIndex: 500,
      background: status === 'offline' ? '#e65100' : '#c62828',
      color: '#fff', padding: '10px 20px', fontSize: 13,
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    }}>
      <span>{msg}</span>
      {status === 'backend-down' && (
        <button
          onClick={() => window.location.reload()}
          style={{ background: 'rgba(255,255,255,.2)', border: 'none', color: '#fff', borderRadius: 6, padding: '4px 12px', cursor: 'pointer', fontSize: 12 }}
        >
          Retry
        </button>
      )}
    </div>
  );
}
