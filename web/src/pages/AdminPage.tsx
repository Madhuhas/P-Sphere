import { useEffect, useState } from 'react';
import {
  AI, Devices, Photos, Stats, Faces, PhotosBatch,
  DeviceRow, LibrarySummary, DuplicateGroup,
  StorageStats, SyncHistoryRow, FaceCluster,
} from '../services/api';
import { useToast } from '../context/ToastContext';

function formatBytes(b: number) {
  if (b < 1024)        return `${b} B`;
  if (b < 1024 ** 2)   return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 ** 3)   return `${(b / 1024 ** 2).toFixed(1)} MB`;
  return `${(b / 1024 ** 3).toFixed(2)} GB`;
}

export default function AdminPage() {
  const [summary,    setSummary]    = useState<LibrarySummary | null>(null);
  const [devices,    setDevices]    = useState<DeviceRow[]>([]);
  const [storage,    setStorage]    = useState<StorageStats | null>(null);
  const [history,    setHistory]    = useState<SyncHistoryRow[]>([]);
  const [clusters,   setClusters]   = useState<FaceCluster[]>([]);
  const [dupes,      setDupes]      = useState<DuplicateGroup[]>([]);
  const [llmOnline,  setLlmOnline]  = useState<boolean | null>(null);
  const [loading,    setLoading]    = useState(true);
  const [pickedDupes, setPickedDupes] = useState<Set<string>>(new Set());
  const [renameId,   setRenameId]   = useState<string | null>(null);
  const [renameName, setRenameName] = useState('');
  const { show } = useToast();

  useEffect(() => {
    Promise.all([
      AI.status(), AI.summary(), Devices.list(),
      Photos.duplicates(), Stats.storage(), Stats.syncHistory(10), Faces.clusters(),
    ]).then(([st, sm, dv, dp, stor, hist, cl]) => {
      setLlmOnline(st.data.available);
      setSummary(sm.data);
      setDevices(dv.data.devices);
      setDupes(dp.data.groups);
      setStorage(stor.data);
      setHistory(hist.data.history);
      setClusters(cl.data.clusters);
    }).catch(() => show('Failed to load admin data', 'error'))
      .finally(() => setLoading(false));
  }, [show]);

  async function removeDevice(id: string) {
    await Devices.remove(id);
    setDevices((p) => p.filter((d) => d.id !== id));
    show('Device removed', 'success');
  }

  async function renameCluster(id: string) {
    if (!renameName.trim()) return;
    try {
      await Faces.rename(id, renameName.trim());
      setClusters((p) => p.map((c) => c.id === id ? { ...c, person_name: renameName.trim() } : c));
      show('Cluster renamed', 'success');
    } catch { show('Rename failed', 'error'); }
    setRenameId(null); setRenameName('');
  }

  async function deleteCluster(id: string) {
    try {
      await Faces.remove(id);
      setClusters((p) => p.filter((c) => c.id !== id));
      show('Cluster deleted', 'success');
    } catch { show('Delete failed', 'error'); }
  }

  function toggleDupePick(id: string) {
    setPickedDupes((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  }

  async function batchDelete() {
    if (!pickedDupes.size) return;
    try {
      const res = await PhotosBatch.delete([...pickedDupes]);
      setDupes((prev) => prev.map((g) => ({ ...g, photos: g.photos.filter((p) => !pickedDupes.has(p.id)) })).filter((g) => g.photos.length > 1));
      setPickedDupes(new Set());
      show(`${res.data.deleted} photo(s) deleted`, 'success');
    } catch { show('Batch delete failed', 'error'); }
  }

  if (loading) return <p style={muted}>Loading…</p>;

  const maxBytes = Math.max(...(storage?.devices.map((d) => d.total_bytes) ?? [1]));

  return (
    <div style={{ padding: 24, maxWidth: 960, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h1 style={h1}>Admin Dashboard</h1>

      {/* Stats row */}
      <div style={statRow}>
        <Stat label="Photos"  value={String(summary?.photoCount ?? 0)} />
        <Stat label="Files"   value={String(storage?.totals?.total_files ?? 0)} />
        <Stat label="Storage" value={formatBytes(Number(storage?.totals?.total_bytes ?? 0))} />
        <Stat label="LLM"     value={llmOnline ? '🟢 Online' : '🔴 Offline'} />
      </div>

      {/* Storage chart */}
      {storage && storage.devices.length > 0 && (
        <Card title="Storage by Device">
          {storage.devices.map((d) => (
            <div key={d.id} style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4, fontSize: 13 }}>
                <span style={{ color: 'var(--text)' }}>{d.name} <Tag>{d.type}</Tag></span>
                <span style={{ color: 'var(--text-muted)' }}>{formatBytes(Number(d.total_bytes))} · {d.file_count} files</span>
              </div>
              <div style={barTrack}>
                <div style={{ ...barFill, width: `${(Number(d.total_bytes) / maxBytes) * 100}%` }} />
              </div>
            </div>
          ))}
        </Card>
      )}

      {/* Sync history */}
      {history.length > 0 && (
        <Card title="Recent Sync Activity">
          <table style={table}>
            <thead>
              <tr>{['Date', 'Device', 'Files', 'Photos', 'Size'].map((h) => (
                <th key={h} style={th}>{h}</th>
              ))}</tr>
            </thead>
            <tbody>
              {history.map((r, i) => (
                <tr key={i} style={{ background: i % 2 === 0 ? 'transparent' : 'rgba(255,255,255,.02)' }}>
                  <td style={td}>{r.sync_date}</td>
                  <td style={td}>{r.device_name} <Tag>{r.device_type}</Tag></td>
                  <td style={td}>{r.file_count}</td>
                  <td style={td}>{r.photo_count}</td>
                  <td style={td}>{formatBytes(Number(r.bytes_synced))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {/* Face clusters */}
      <Card title={`Face Clusters (${clusters.length})`}>
        {clusters.length === 0
          ? <p style={muted}>No clusters yet. Upload photos to detect faces.</p>
          : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 12 }}>
              {clusters.map((c) => (
                <div key={c.id} style={clusterCard}>
                  <img src={c.cover_url} alt="" style={clusterThumb} />
                  {renameId === c.id ? (
                    <div style={{ padding: '8px 8px 10px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <input
                        style={renameInput}
                        value={renameName}
                        onChange={(e) => setRenameName(e.target.value)}
                        placeholder="Person's name"
                        autoFocus
                        onKeyDown={(e) => { if (e.key === 'Enter') renameCluster(c.id); if (e.key === 'Escape') setRenameId(null); }}
                      />
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button style={{ ...miniBtn, background: 'var(--primary)' }} onClick={() => renameCluster(c.id)}>Save</button>
                        <button style={miniBtn} onClick={() => setRenameId(null)}>✕</button>
                      </div>
                    </div>
                  ) : (
                    <div style={{ padding: '6px 8px 10px' }}>
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 2 }}>
                        {c.person_name ?? c.label ?? 'Unknown'}
                      </div>
                      <div style={muted}>{c.photo_count} photos</div>
                      <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                        <button style={miniBtn} onClick={() => { setRenameId(c.id); setRenameName(c.person_name ?? ''); }}>Rename</button>
                        <button style={{ ...miniBtn, background: 'var(--danger)' }} onClick={() => deleteCluster(c.id)}>Delete</button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )
        }
      </Card>

      {/* Devices */}
      <Card title={`Registered Devices (${devices.length})`}>
        {devices.length === 0
          ? <p style={muted}>No devices.</p>
          : devices.map((d) => (
            <div key={d.id} style={devRow}>
              <div style={{ fontSize: 14 }}>
                <strong style={{ color: 'var(--text)' }}>{d.name}</strong>
                <Tag>{d.type}</Tag>
                <span style={muted}> · last seen {d.last_seen?.slice(0, 10) ?? 'never'}</span>
              </div>
              <button style={{ ...miniBtn, background: 'var(--danger)' }} onClick={() => removeDevice(d.id)}>Remove</button>
            </div>
          ))
        }
      </Card>

      {/* Near-duplicates with batch delete */}
      <Card title={`Near-Duplicate Groups (${dupes.length})`}>
        {pickedDupes.size > 0 && (
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 14 }}>
            <button style={{ ...miniBtn, background: 'var(--danger)', padding: '8px 16px', fontSize: 13 }}
              onClick={batchDelete}>
              Delete {pickedDupes.size} selected
            </button>
            <button style={miniBtn} onClick={() => setPickedDupes(new Set())}>Clear selection</button>
          </div>
        )}
        {dupes.length === 0
          ? <p style={muted}>No duplicates found.</p>
          : dupes.slice(0, 12).map((g, i) => (
            <div key={i} style={dupeRow}>
              <span style={simBadge}>{Math.round(g.similarity * 100)}%</span>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {g.photos.map((p) => {
                  const picked = pickedDupes.has(p.id);
                  return (
                    <div key={p.id} style={{ position: 'relative', cursor: 'pointer' }}
                      onClick={() => toggleDupePick(p.id)}>
                      <img src={p.thumb_url} alt="" style={{ ...dupeThumb, outline: picked ? '3px solid var(--accent)' : 'none' }} />
                      {picked && <div style={checkMark}>✓</div>}
                    </div>
                  );
                })}
              </div>
            </div>
          ))
        }
      </Card>

      {/* Library summary */}
      {summary && (
        <Card title="Library Summary">
          <p style={{ ...muted, lineHeight: 1.7, marginBottom: 10 }}>{summary.summary}</p>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {summary.topLocations.map((l) => <Tag key={l}>{l}</Tag>)}
            {summary.topTags.map((t) => <Tag key={t} blue>{t}</Tag>)}
          </div>
        </Card>
      )}
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

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ background: 'var(--surface)', borderRadius: 10, padding: '14px 18px', flex: 1 }}>
      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: .5 }}>{label}</div>
      <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--text)' }}>{value}</div>
    </div>
  );
}

function Tag({ children, blue }: { children: React.ReactNode; blue?: boolean }) {
  return <span style={{ background: blue ? 'var(--surface2)' : 'var(--border)', color: blue ? 'var(--accent)' : 'var(--text-muted)', borderRadius: 5, padding: '1px 7px', fontSize: 11, marginLeft: 5 }}>{children}</span>;
}

const h1:        React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const statRow:   React.CSSProperties = { display: 'flex', gap: 12 };
const muted:     React.CSSProperties = { color: 'var(--text-muted)', fontSize: 13 };
const barTrack:  React.CSSProperties = { background: 'var(--border)', borderRadius: 4, height: 8, overflow: 'hidden' };
const barFill:   React.CSSProperties = { background: 'var(--primary)', height: '100%', borderRadius: 4, transition: 'width .4s ease' };
const table:     React.CSSProperties = { width: '100%', borderCollapse: 'collapse', fontSize: 13 };
const th:        React.CSSProperties = { textAlign: 'left', color: 'var(--text-muted)', padding: '4px 10px 10px', fontWeight: 500, borderBottom: '1px solid var(--border)' };
const td:        React.CSSProperties = { padding: '8px 10px', color: 'var(--text)', verticalAlign: 'middle' };
const devRow:    React.CSSProperties = { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px solid var(--border)' };
const dupeRow:   React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0', borderBottom: '1px solid var(--border)' };
const simBadge:  React.CSSProperties = { background: 'var(--surface2)', color: 'var(--accent)', borderRadius: 6, padding: '3px 8px', fontSize: 12, whiteSpace: 'nowrap' };
const dupeThumb: React.CSSProperties = { width: 60, height: 60, borderRadius: 6, objectFit: 'cover', display: 'block' };
const checkMark: React.CSSProperties = { position: 'absolute', inset: 0, background: 'rgba(21,101,192,.5)', borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 700, fontSize: 18 };
const clusterCard: React.CSSProperties = { background: 'var(--surface2)', borderRadius: 10, overflow: 'hidden' };
const clusterThumb:React.CSSProperties = { width: '100%', aspectRatio: '1', objectFit: 'cover', display: 'block' };
const miniBtn:   React.CSSProperties = { background: 'var(--border)', color: 'var(--text)', border: 'none', borderRadius: 6, padding: '5px 10px', cursor: 'pointer', fontSize: 12 };
const renameInput: React.CSSProperties = { background: 'var(--input-bg)', border: '1px solid var(--border)', borderRadius: 6, padding: '6px 8px', color: 'var(--text)', fontSize: 13, width: '100%' };
