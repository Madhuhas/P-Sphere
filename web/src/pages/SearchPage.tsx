import { useEffect, useState } from 'react';
import { AI, SearchResponse } from '../services/api';
import PhotoModal from '../components/PhotoModal';

export default function SearchPage() {
  const [query,     setQuery]     = useState('');
  const [result,    setResult]    = useState<SearchResponse | null>(null);
  const [loading,   setLoading]   = useState(false);
  const [selected,  setSelected]  = useState<string | null>(null);
  const [llmOnline, setLlmOnline] = useState<boolean | null>(null);

  useEffect(() => {
    AI.status().then((r) => setLlmOnline(r.data.available)).catch(() => setLlmOnline(false));
  }, []);

  async function search(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setLoading(true);
    setResult(null);
    try {
      const res = await AI.search(query.trim());
      setResult(res.data);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 900 }}>
      <h1 style={h1}>AI Photo Search</h1>

      {llmOnline === false && (
        <div style={llmBanner}>
          🤖 LLM is offline — search will fall back to keyword matching.
          Start Ollama or LM Studio to enable AI-ranked results.
        </div>
      )}

      <form onSubmit={search} style={form}>
        <input
          style={input}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder='e.g. "beach trip 2023" or "photos with the dog"'
        />
        <button style={btn} type="submit" disabled={loading}>
          {loading ? 'Searching…' : 'Search'}
        </button>
      </form>

      {result && (
        <>
          <div style={explanation}>
            {result.fallback ? '⚠ ' : '✦ '}{result.explanation}
          </div>

          {result.photos.length === 0
            ? <p style={muted}>No matching photos found.</p>
            : (
              <div style={grid}>
                {result.photos.map((p) => (
                  <div key={p.id} style={cell} onClick={() => setSelected(p.id)}>
                    <img src={p.thumb_url} alt="" style={thumb} />
                    <div style={overlay}>
                      <span style={score}>{Math.round(p.score * 100)}%</span>
                      {p.tags?.slice(0, 2).map((t) => (
                        <span key={t} style={tag}>{t}</span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )
          }
        </>
      )}

      {selected && <PhotoModal photoId={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

const llmBanner:  React.CSSProperties = { background: '#4a2f00', border: '1px solid #e65100', borderRadius: 8, padding: '10px 16px', color: '#ffcc80', fontSize: 13, marginBottom: 16, lineHeight: 1.5 };
const h1:         React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)', marginBottom: 20 };
const form:       React.CSSProperties = { display: 'flex', gap: 10, marginBottom: 20 };
const input:      React.CSSProperties = { flex: 1, background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8, padding: '12px 16px', color: 'var(--text)', fontSize: 15 };
const btn:        React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '12px 24px', cursor: 'pointer', fontSize: 15 };
const explanation:React.CSSProperties = { background: 'var(--surface)', borderRadius: 8, padding: '12px 16px', color: 'var(--accent)', fontSize: 13, marginBottom: 20, lineHeight: 1.5 };
const grid:       React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 8 };
const cell:       React.CSSProperties = { position: 'relative', aspectRatio: '1', cursor: 'pointer', borderRadius: 8, overflow: 'hidden', background: 'var(--surface)' };
const thumb:      React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover' };
const overlay:    React.CSSProperties = { position: 'absolute', bottom: 0, left: 0, right: 0, padding: '20px 6px 5px', background: 'linear-gradient(transparent,rgba(0,0,0,.75))', display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'flex-end' };
const score:      React.CSSProperties = { background: 'var(--primary)', color: '#fff', borderRadius: 4, padding: '1px 6px', fontSize: 11, fontWeight: 700 };
const tag:        React.CSSProperties = { background: 'rgba(255,255,255,.15)', color: '#ddd', borderRadius: 4, padding: '1px 5px', fontSize: 10 };
const muted:      React.CSSProperties = { color: 'var(--text-muted)', fontSize: 14 };
