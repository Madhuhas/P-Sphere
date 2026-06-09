import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Albums, AlbumRow } from '../services/api';
import { SkeletonAlbumGrid } from '../components/Skeleton';
import { useToast } from '../context/ToastContext';

export default function AlbumsPage() {
  const [albums,     setAlbums]     = useState<AlbumRow[]>([]);
  const [loading,    setLoading]    = useState(true);
  const [generating, setGenerating] = useState(false);
  const [hovered,    setHovered]    = useState<string | null>(null);
  const navigate = useNavigate();
  const { show } = useToast();

  useEffect(() => {
    Albums.list()
      .then((r) => setAlbums(r.data.albums))
      .catch(() => show('Failed to load albums.', 'error'))
      .finally(() => setLoading(false));
  }, [show]);

  async function generate() {
    setGenerating(true);
    try {
      await Albums.generate('generate');
      const res = await Albums.list();
      setAlbums(res.data.albums);
      show('Smart albums updated!', 'success');
    } catch {
      show('Failed to generate albums. Is the LLM running?', 'error');
    } finally {
      setGenerating(false);
    }
  }

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24 }}>
        <h1 style={h1}>Albums</h1>
        <button style={{ ...btn, opacity: generating ? .6 : 1 }} onClick={generate} disabled={generating}>
          {generating ? 'Generating…' : '✦ Generate Smart Albums'}
        </button>
      </div>

      {loading ? <SkeletonAlbumGrid count={8} /> : albums.length === 0 ? (
        <div style={empty}>
          <p style={{ fontSize: 18, marginBottom: 8 }}>No albums yet</p>
          <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Click "Generate Smart Albums" to create them automatically from your photos.</p>
        </div>
      ) : (
        <div style={grid}>
          {albums.map((a) => (
            <div
              key={a.id}
              style={{ ...card, transform: hovered === a.id ? 'translateY(-3px)' : 'none', boxShadow: hovered === a.id ? '0 8px 24px rgba(0,0,0,.3)' : 'none' }}
              onClick={() => navigate(`/albums/${a.id}`)}
              onMouseEnter={() => setHovered(a.id)}
              onMouseLeave={() => setHovered(null)}
            >
              <div style={cover}>
                {a.cover_url
                  ? <img src={a.cover_url} alt="" style={coverImg} />
                  : <div style={{ ...coverImg, background: 'var(--surface2)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 32 }}>📁</div>
                }
                <span style={badge}>{a.type}</span>
              </div>
              <div style={info}>
                <div style={name}>{a.name}</div>
                <div style={sub}>{a.photo_count} photos</div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const h1:      React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const btn:     React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 18px', cursor: 'pointer', fontSize: 13, transition: 'opacity .15s' };
const grid:    React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 16 };
const card:    React.CSSProperties = { background: 'var(--surface)', borderRadius: 12, overflow: 'hidden', cursor: 'pointer', transition: 'transform .2s, box-shadow .2s' };
const cover:   React.CSSProperties = { position: 'relative', aspectRatio: '4/3', background: 'var(--surface2)' };
const coverImg:React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover' };
const badge:   React.CSSProperties = { position: 'absolute', top: 8, left: 8, background: 'rgba(0,0,0,.6)', color: 'var(--accent)', borderRadius: 6, padding: '2px 8px', fontSize: 11, fontWeight: 600 };
const info:    React.CSSProperties = { padding: '10px 14px 14px' };
const name:    React.CSSProperties = { fontWeight: 600, fontSize: 14, color: 'var(--text)', marginBottom: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' };
const sub:     React.CSSProperties = { color: 'var(--text-muted)', fontSize: 13 };
const empty:   React.CSSProperties = { textAlign: 'center', padding: '60px 20px', color: 'var(--text)' };
