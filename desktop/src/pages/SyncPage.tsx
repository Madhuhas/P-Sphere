import { useEffect, useRef, useState } from 'react';
import { setAuthToken } from '../services/api';

type SyncStage = 'idle' | 'scan' | 'check' | 'upload' | 'done' | 'error';

interface LogLine { text: string; stage: SyncStage }

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:3000';

export default function SyncPage() {
  const [folder,      setFolder]      = useState('');
  const [jwt,         setJwt]         = useState(localStorage.getItem('psphere_jwt') ?? '');
  const [deviceToken, setDeviceToken] = useState(localStorage.getItem('psphere_device_token') ?? '');
  const [stage,       setStage]       = useState<SyncStage>('idle');
  const [done,        setDone]        = useState(0);
  const [total,       setTotal]       = useState(0);
  const [log,         setLog]         = useState<LogLine[]>([]);
  const logRef = useRef<HTMLDivElement>(null);
  const cleanupRef = useRef<(() => void) | null>(null);

  // Subscribe to progress events from main process
  useEffect(() => {
    cleanupRef.current = window.psphere.onSyncProgress((p) => {
      setStage(p.stage as SyncStage);
      if (p.done  !== undefined) setDone(p.done);
      if (p.total !== undefined) setTotal(p.total);
      setLog((prev) => [...prev, { text: p.message, stage: p.stage as SyncStage }]);
    });
    return () => cleanupRef.current?.();
  }, []);

  // Auto-scroll log
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [log]);

  async function pickFolder() {
    const p = await window.psphere.selectFolder();
    if (p) setFolder(p);
  }

  function saveCredentials() {
    localStorage.setItem('psphere_jwt',          jwt);
    localStorage.setItem('psphere_device_token', deviceToken);
    setAuthToken(jwt);
    setLog((prev) => [...prev, { text: 'Credentials saved.', stage: 'idle' }]);
  }

  async function startSync() {
    if (!folder)      { setLog((p) => [...p, { text: 'Select a folder first.', stage: 'error' }]); return; }
    if (!jwt)         { setLog((p) => [...p, { text: 'Enter your JWT token.', stage: 'error' }]);  return; }
    if (!deviceToken) { setLog((p) => [...p, { text: 'Enter your device token.', stage: 'error' }]); return; }

    setLog([]);
    setDone(0);
    setTotal(0);
    setStage('scan');

    await window.psphere.startSync({ folder, baseUrl: BASE_URL, jwt, deviceToken });
  }

  const busy = stage === 'scan' || stage === 'check' || stage === 'upload';
  const pct  = total > 0 ? Math.round((done / total) * 100) : 0;

  return (
    <div style={styles.page}>
      <h1 style={styles.h1}>Folder Sync</h1>

      <Section title="Credentials">
        <label style={styles.label}>JWT Token</label>
        <input style={styles.input} type="password" value={jwt}
          onChange={(e) => setJwt(e.target.value)} placeholder="Paste token from login" />
        <label style={{ ...styles.label, marginTop: 10 }}>Device Token</label>
        <input style={styles.input} type="password" value={deviceToken}
          onChange={(e) => setDeviceToken(e.target.value)} placeholder="Token from device registration" />
        <button style={styles.btn} onClick={saveCredentials}>Save</button>
      </Section>

      <Section title="Select Folder">
        <div style={{ display:'flex', gap:12, alignItems:'center' }}>
          <input style={{ ...styles.input, flex:1 }} readOnly value={folder || 'No folder selected'} />
          <button style={styles.btn} onClick={pickFolder}>Browse…</button>
        </div>
      </Section>

      <Section title="Sync">
        <div style={{ display: 'flex', gap: 10 }}>
          <button style={{ ...styles.btn, opacity: busy ? 0.5 : 1, flex: 1 }}
            onClick={startSync} disabled={busy}>
            {busy ? 'Syncing…' : 'Start Sync'}
          </button>
          {(stage === 'error' || (stage === 'done' && log.some((l) => l.text.includes('✗')))) && (
            <button style={{ ...styles.btn, background: '#37474f' }} onClick={startSync}>
              ↩ Retry
            </button>
          )}
        </div>

        {/* Progress bar */}
        {stage === 'upload' && total > 0 && (
          <div style={styles.barTrack}>
            <div style={{ ...styles.barFill, width:`${pct}%` }} />
            <span style={styles.barLabel}>{done}/{total} ({pct}%)</span>
          </div>
        )}

        {/* Status badge */}
        {stage !== 'idle' && (
          <span style={{ ...styles.badge, background: stageBg(stage) }}>
            {stage.toUpperCase()}
          </span>
        )}
      </Section>

      {log.length > 0 && (
        <Section title="Log">
          <div style={styles.log} ref={logRef}>
            {log.map((line, i) => (
              <div key={i} style={{ color: lineColor(line.stage) }}>{line.text}</div>
            ))}
          </div>
        </Section>
      )}
    </div>
  );
}

function stageBg(s: SyncStage) {
  return s === 'done' ? '#2e7d32' : s === 'error' ? '#c62828' : '#1565c0';
}
function lineColor(s: SyncStage) {
  return s === 'error' ? '#ef9a9a' : s === 'done' ? '#a5d6a7' : '#ccc';
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ background:'#1e2a3a', borderRadius:12, padding:20, marginBottom:16 }}>
      <h2 style={{ fontSize:15, fontWeight:600, color:'#90caf9', marginBottom:12 }}>{title}</h2>
      {children}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page:     { padding:32, maxWidth:700, color:'#eee' },
  h1:       { fontSize:26, fontWeight:700, marginBottom:24 },
  label:    { display:'block', fontSize:12, color:'#888', marginBottom:4 },
  input:    { background:'#111', border:'1px solid #333', borderRadius:8, padding:'10px 14px',
              color:'#eee', fontSize:14, width:'100%', boxSizing:'border-box', marginBottom:8 },
  btn:      { background:'#1565c0', color:'#fff', border:'none', borderRadius:8,
              padding:'10px 20px', cursor:'pointer', fontSize:14, marginTop:4 },
  log:      { background:'#111', borderRadius:8, padding:16, fontFamily:'monospace',
              fontSize:12, lineHeight:1.8, maxHeight:280, overflowY:'auto' },
  barTrack: { position:'relative', background:'#333', borderRadius:6, height:8, marginTop:12, overflow:'hidden' },
  barFill:  { background:'#1565c0', height:'100%', transition:'width 0.2s', borderRadius:6 },
  barLabel: { position:'absolute', right:0, top:-20, fontSize:12, color:'#888' },
  badge:    { display:'inline-block', borderRadius:6, padding:'3px 10px', fontSize:11,
              fontWeight:700, letterSpacing:1, marginLeft:12 },
};
