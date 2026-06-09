import { useEffect, useState } from 'react';
import { AI, LibrarySummary } from '../services/api';

export default function DashboardPage() {
  const [summary, setSummary] = useState<LibrarySummary | null>(null);
  const [llmOnline, setLlmOnline] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([AI.status(), AI.summary()])
      .then(([statusRes, summaryRes]) => {
        setLlmOnline(statusRes.data.available);
        setSummary(summaryRes.data);
      })
      .catch(() => setError('Could not reach backend. Is the core server running?'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <p style={styles.muted}>Loading…</p>;
  if (error)   return <p style={{ color:'#ef5350' }}>{error}</p>;

  return (
    <div style={styles.page}>
      <h1 style={styles.h1}>Dashboard</h1>

      <div style={styles.cards}>
        <StatCard label="Photos"   value={String(summary?.photoCount ?? 0)} />
        <StatCard label="LLM"      value={llmOnline ? '🟢 Online' : '🔴 Offline'} />
        <StatCard label="Earliest" value={summary?.dateRange.earliest?.slice(0,10) ?? '—'} />
        <StatCard label="Latest"   value={summary?.dateRange.latest?.slice(0,10)   ?? '—'} />
      </div>

      {summary && (
        <>
          <Section title="Library Summary">
            <p style={styles.prose}>{summary.summary}</p>
          </Section>

          <Section title="Top Locations">
            <TagList items={summary.topLocations} />
          </Section>

          <Section title="Top Tags">
            <TagList items={summary.topTags} />
          </Section>
        </>
      )}
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div style={styles.card}>
      <div style={styles.cardLabel}>{label}</div>
      <div style={styles.cardValue}>{value}</div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={styles.section}>
      <h2 style={styles.h2}>{title}</h2>
      {children}
    </div>
  );
}

function TagList({ items }: { items: string[] }) {
  if (items.length === 0) return <p style={styles.muted}>None</p>;
  return (
    <div style={{ display:'flex', flexWrap:'wrap', gap:8 }}>
      {items.map((t) => <span key={t} style={styles.tag}>{t}</span>)}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page:      { padding:32, maxWidth:900, color:'#eee' },
  h1:        { fontSize:26, fontWeight:700, marginBottom:24 },
  h2:        { fontSize:16, fontWeight:600, marginBottom:12, color:'#90caf9' },
  cards:     { display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap:16, marginBottom:32 },
  card:      { background:'#1e2a3a', borderRadius:12, padding:20 },
  cardLabel: { fontSize:12, color:'#888', marginBottom:6 },
  cardValue: { fontSize:22, fontWeight:700 },
  section:   { background:'#1e2a3a', borderRadius:12, padding:20, marginBottom:16 },
  prose:     { lineHeight:1.7, color:'#ccc' },
  tag:       { background:'#162447', color:'#90caf9', borderRadius:6, padding:'4px 10px', fontSize:13 },
  muted:     { color:'#888' },
};
