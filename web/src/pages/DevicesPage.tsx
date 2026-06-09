import { useEffect, useState } from 'react';
import { Devices, Sync, DeviceRow, SyncConflict } from '../services/api';
import { useToast } from '../context/ToastContext';

function isOnline(lastSeen: string | null): boolean {
  if (!lastSeen) return false;
  return Date.now() - new Date(lastSeen).getTime() < 5 * 60 * 1000; // 5 min
}

function timeAgo(ts: string | null): string {
  if (!ts) return 'Never';
  const diff = Date.now() - new Date(ts).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1)   return 'Just now';
  if (m < 60)  return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24)  return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export default function DevicesPage() {
  const [devices,   setDevices]   = useState<DeviceRow[]>([]);
  const [conflicts, setConflicts] = useState<SyncConflict[]>([]);
  const [loading,   setLoading]   = useState(true);
  const [renameId,  setRenameId]  = useState<string | null>(null);
  const [renameName, setRenameName] = useState('');
  const { show } = useToast();

  useEffect(() => {
    Promise.all([Devices.list(), Sync.conflicts()])
      .then(([dv, cf]) => { setDevices(dv.data.devices); setConflicts(cf.data.conflicts); })
      .catch(() => show('Failed to load devices', 'error'))
      .finally(() => setLoading(false));
  }, [show]);

  async function toggleSync(device: DeviceRow) {
    try {
      const res = await Devices.toggleSync(device.id, !device.sync_enabled);
      setDevices((p) => p.map((d) => d.id === device.id ? res.data.device : d));
      show(`Sync ${res.data.device.sync_enabled ? 'enabled' : 'disabled'} for ${device.name}`, 'success');
    } catch { show('Failed to update device', 'error'); }
  }

  async function renameDevice(id: string) {
    if (!renameName.trim()) return;
    try {
      const res = await Devices.rename(id, renameName.trim());
      setDevices((p) => p.map((d) => d.id === id ? res.data.device : d));
      show('Device renamed', 'success');
    } catch { show('Rename failed', 'error'); }
    setRenameId(null); setRenameName('');
  }

  async function removeDevice(id: string, name: string) {
    try {
      await Devices.remove(id);
      setDevices((p) => p.filter((d) => d.id !== id));
      show(`${name} removed`, 'success');
    } catch { show('Remove failed', 'error'); }
  }

  async function resolve(conflict: SyncConflict, keepFileId: string) {
    try {
      await Sync.resolve(conflict.id, keepFileId);
      setConflicts((p) => p.filter((c) => c.id !== conflict.id));
      show('Conflict resolved', 'success');
    } catch { show('Resolution failed', 'error'); }
  }

  if (loading) return <p style={muted}>Loading…</p>;

  const onlineCount = devices.filter((d) => isOnline(d.last_seen)).length;

  return (
    <div style={{ padding: 24, maxWidth: 860, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
        <h1 style={h1}>Devices</h1>
        <span style={muted}>{onlineCount} online · {devices.length} total</span>
      </div>

      {/* Device list */}
      <Card title="All Devices">
        {devices.length === 0
          ? <p style={muted}>No devices registered yet.</p>
          : devices.map((d) => (
            <div key={d.id} style={devRow}>
              {/* Status dot + name */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1 }}>
                <span style={{ ...dot, background: isOnline(d.last_seen) ? '#4caf50' : '#555' }} />
                {renameId === d.id ? (
                  <input
                    style={inlineInput}
                    value={renameName}
                    onChange={(e) => setRenameName(e.target.value)}
                    autoFocus
                    onKeyDown={(e) => { if (e.key === 'Enter') renameDevice(d.id); if (e.key === 'Escape') setRenameId(null); }}
                  />
                ) : (
                  <div>
                    <span style={{ fontWeight: 600, color: 'var(--text)', fontSize: 14 }}>{d.name}</span>
                    <span style={badge}>{d.type}</span>
                    <span style={muted}> · {timeAgo(d.last_seen)}</span>
                  </div>
                )}
              </div>

              {/* Actions */}
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {/* Sync toggle */}
                <button
                  style={{ ...toggleBtn, background: d.sync_enabled ? '#1b5e20' : 'var(--border)', color: d.sync_enabled ? '#a5d6a7' : 'var(--text-muted)' }}
                  onClick={() => toggleSync(d)}
                  title={d.sync_enabled ? 'Disable sync' : 'Enable sync'}
                >
                  {d.sync_enabled ? '⟳ Sync On' : '⏸ Sync Off'}
                </button>
                <button style={miniBtn} onClick={() => { setRenameId(d.id); setRenameName(d.name); }}>Rename</button>
                <button style={{ ...miniBtn, background: 'var(--danger)', color: '#fff' }} onClick={() => removeDevice(d.id, d.name)}>Remove</button>
              </div>
            </div>
          ))
        }
      </Card>

      {/* Cross-device progress */}
      <Card title="Sync Status Overview">
        {devices.length === 0
          ? <p style={muted}>Register devices to see sync status.</p>
          : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 12 }}>
              {devices.map((d) => (
                <div key={d.id} style={statusCard}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                    <span style={{ ...dot, width: 10, height: 10, background: isOnline(d.last_seen) ? '#4caf50' : '#555' }} />
                    <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--text)' }}>{d.name}</span>
                  </div>
                  <div style={muted}>{d.type}</div>
                  <div style={{ ...muted, marginTop: 4 }}>Last seen: {timeAgo(d.last_seen)}</div>
                  <div style={{ marginTop: 6 }}>
                    <span style={{ ...badge, background: d.sync_enabled ? '#1b5e20' : 'var(--border)', color: d.sync_enabled ? '#a5d6a7' : 'var(--text-muted)' }}>
                      {d.sync_enabled ? 'Sync enabled' : 'Sync disabled'}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )
        }
      </Card>

      {/* Conflict resolution */}
      <Card title={`File Conflicts (${conflicts.length})`}>
        {conflicts.length === 0
          ? <p style={muted}>No conflicts detected. All devices are in sync.</p>
          : conflicts.map((c) => (
            <div key={c.id} style={conflictRow}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 4, wordBreak: 'break-all' }}>
                  📄 {c.path}
                </div>
                <div style={muted}>Detected: {c.created_at.slice(0, 10)}</div>
              </div>
              <div style={conflictVersions}>
                <VersionCard
                  device={c.device_a_name}
                  modified={c.modified_a}
                  onKeep={() => resolve(c, c.file_a_id)}
                />
                <div style={{ color: 'var(--text-muted)', fontSize: 12, alignSelf: 'center' }}>vs</div>
                <VersionCard
                  device={c.device_b_name}
                  modified={c.modified_b}
                  onKeep={() => resolve(c, c.file_b_id)}
                />
              </div>
            </div>
          ))
        }
      </Card>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ background: 'var(--surface)', borderRadius: 12, padding: 20 }}>
      <h2 style={{ fontSize: 15, fontWeight: 600, color: 'var(--accent)', marginBottom: 16 }}>{title}</h2>
      {children}
    </div>
  );
}

function VersionCard({ device, modified, onKeep }: { device: string; modified: string; onKeep: () => void }) {
  return (
    <div style={versionCard}>
      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 2 }}>{device}</div>
      <div style={muted}>Modified: {modified.slice(0, 16).replace('T', ' ')}</div>
      <button style={{ ...miniBtn, marginTop: 8, background: 'var(--primary)', color: '#fff' }} onClick={onKeep}>Keep this</button>
    </div>
  );
}

const h1:       React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const muted:    React.CSSProperties = { color: 'var(--text-muted)', fontSize: 13 };
const devRow:   React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0', borderBottom: '1px solid var(--border)' };
const dot:      React.CSSProperties = { display: 'inline-block', width: 8, height: 8, borderRadius: '50%', flexShrink: 0 };
const badge:    React.CSSProperties = { background: 'var(--surface2)', color: 'var(--text-muted)', borderRadius: 5, padding: '1px 7px', fontSize: 11, marginLeft: 6 };
const toggleBtn:React.CSSProperties = { border: 'none', borderRadius: 6, padding: '5px 12px', cursor: 'pointer', fontSize: 12, fontWeight: 600, transition: 'background .2s' };
const miniBtn:  React.CSSProperties = { background: 'var(--border)', color: 'var(--text)', border: 'none', borderRadius: 6, padding: '5px 10px', cursor: 'pointer', fontSize: 12 };
const inlineInput: React.CSSProperties = { background: 'var(--input-bg)', border: '1px solid var(--border)', borderRadius: 6, padding: '4px 8px', color: 'var(--text)', fontSize: 13 };
const statusCard:   React.CSSProperties = { background: 'var(--surface2)', borderRadius: 10, padding: 14 };
const conflictRow:  React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 12, padding: '14px 0', borderBottom: '1px solid var(--border)' };
const conflictVersions: React.CSSProperties = { display: 'flex', gap: 12, alignItems: 'flex-start' };
const versionCard:  React.CSSProperties = { background: 'var(--surface2)', borderRadius: 8, padding: 12, flex: 1 };
