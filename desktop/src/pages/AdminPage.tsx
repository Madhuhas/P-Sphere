import { useEffect, useState } from 'react';
import { Devices, AI, DeviceRow, CleanupSuggestion } from '../services/api';

export default function AdminPage() {
  const [devices, setDevices]     = useState<DeviceRow[]>([]);
  const [cleanup, setCleanup]     = useState<CleanupSuggestion[]>([]);
  const [cleanupSummary, setCleanupSummary] = useState('');
  const [albumMsg, setAlbumMsg]   = useState('');
  const [loading, setLoading]     = useState(true);

  useEffect(() => {
    Promise.all([Devices.list(), AI.cleanup()])
      .then(([devRes, clRes]) => {
        setDevices(devRes.data.devices);
        setCleanup(clRes.data.suggestions);
        setCleanupSummary(clRes.data.summary);
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  async function removeDevice(id: string) {
    await Devices.remove(id);
    setDevices((prev) => prev.filter((d) => d.id !== id));
  }

  async function createAlbums() {
    setAlbumMsg('Creating smart albums…');
    try {
      const res = await AI.createAlbums();
      const { created, explanation } = res.data;
      setAlbumMsg(`Created ${created.length} album(s). ${explanation}`);
    } catch {
      setAlbumMsg('Failed to create albums. Is the LLM running?');
    }
  }

  if (loading) return <p style={{ padding:32, color:'#888' }}>Loading…</p>;

  return (
    <div style={styles.page}>
      <h1 style={styles.h1}>Admin Console</h1>

      {/* Devices */}
      <Section title={`Registered Devices (${devices.length})`}>
        {devices.length === 0
          ? <p style={styles.muted}>No devices registered.</p>
          : devices.map((d) => (
            <div key={d.id} style={styles.row}>
              <div>
                <strong>{d.name}</strong>
                <span style={styles.badge}>{d.type}</span>
                <span style={styles.muted}> · last seen {d.last_seen?.slice(0,10) ?? 'never'}</span>
              </div>
              <button style={styles.dangerBtn} onClick={() => removeDevice(d.id)}>Remove</button>
            </div>
          ))
        }
      </Section>

      {/* Smart Albums */}
      <Section title="Smart Albums">
        <p style={styles.muted}>Let the AI group your photos into meaningful albums automatically.</p>
        <button style={styles.btn} onClick={createAlbums}>Create Smart Albums</button>
        {albumMsg && <p style={{ marginTop:12, color:'#90caf9' }}>{albumMsg}</p>}
      </Section>

      {/* Cleanup */}
      <Section title={`Cleanup Suggestions (${cleanup.length})`}>
        {cleanupSummary && <p style={{ ...styles.muted, marginBottom:12 }}>{cleanupSummary}</p>}
        {cleanup.length === 0
          ? <p style={styles.muted}>Your library looks clean!</p>
          : cleanup.slice(0, 20).map((s) => (
            <div key={s.photoId} style={styles.cleanupRow}>
              <img src={`http://localhost:3000${s.thumb_url}`} style={styles.thumb} alt="" />
              <span style={styles.reason}>{s.reason}</span>
            </div>
          ))
        }
      </Section>
    </div>
  );
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
  page:       { padding:32, maxWidth:800, color:'#eee' },
  h1:         { fontSize:26, fontWeight:700, marginBottom:24 },
  row:        { display:'flex', justifyContent:'space-between', alignItems:'center', padding:'10px 0', borderBottom:'1px solid #2a2a3a' },
  badge:      { background:'#162447', color:'#90caf9', borderRadius:6, padding:'2px 8px', fontSize:12, marginLeft:8 },
  muted:      { color:'#888', fontSize:13 },
  btn:        { background:'#1565c0', color:'#fff', border:'none', borderRadius:8, padding:'10px 20px', cursor:'pointer', fontSize:14 },
  dangerBtn:  { background:'#c62828', color:'#fff', border:'none', borderRadius:6, padding:'6px 14px', cursor:'pointer', fontSize:13 },
  cleanupRow: { display:'flex', alignItems:'center', gap:12, padding:'8px 0', borderBottom:'1px solid #2a2a3a' },
  thumb:      { width:56, height:56, borderRadius:6, objectFit:'cover' },
  reason:     { fontSize:13, color:'#ccc' },
};
