import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Albums, AlbumRow, PhotoRow } from '../services/api';
import PhotoModal from '../components/PhotoModal';
import { SkeletonPhotoGrid } from '../components/Skeleton';
import { useToast } from '../context/ToastContext';

const TYPE_LABELS: Record<string, string> = {
  trip: '✈ Trip', people: '👤 People', pets: '🐾 Pets',
  place: '📍 Place', smart: '✦ Smart',
};

export default function AlbumDetailPage() {
  const { id }     = useParams<{ id: string }>();
  const navigate   = useNavigate();
  const { show }   = useToast();

  const [album,    setAlbum]    = useState<AlbumRow | null>(null);
  const [photos,   setPhotos]   = useState<PhotoRow[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!id) return;
    Albums.get(id)
      .then((r) => { setAlbum(r.data.album); setPhotos(r.data.photos); })
      .catch(() => show('Failed to load album.', 'error'))
      .finally(() => setLoading(false));
  }, [id, show]);

  async function deleteAlbum() {
    if (!id || !album) return;
    if (!confirm(`Delete album "${album.name}"? This won't delete the photos.`)) return;
    setDeleting(true);
    try {
      await Albums.remove(id);
      show('Album deleted.', 'success');
      navigate('/albums');
    } catch {
      show('Failed to delete album.', 'error');
      setDeleting(false);
    }
  }

  async function setCover(photoId: string) {
    if (!id) return;
    try {
      await Albums.setCover(id, photoId);
      show('Cover photo updated.', 'success');
    } catch {
      show('Failed to update cover.', 'error');
    }
  }

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button style={backBtn} onClick={() => navigate('/albums')}>← Albums</button>
        <div style={{ flex: 1 }}>
          {album ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <h1 style={h1}>{album.name}</h1>
              {album.type && TYPE_LABELS[album.type] && (
                <span style={typeBadge}>{TYPE_LABELS[album.type]}</span>
              )}
              <span style={countBadge}>{album.photo_count} photos</span>
            </div>
          ) : (
            <div style={{ height: 28, width: 200, background: 'var(--border)', borderRadius: 6 }} />
          )}
        </div>
        {album && (
          <button
            style={{ ...dangerBtn, opacity: deleting ? .5 : 1 }}
            onClick={deleteAlbum}
            disabled={deleting}
          >
            {deleting ? 'Deleting…' : 'Delete album'}
          </button>
        )}
      </div>

      {/* Photo grid */}
      {loading ? (
        <SkeletonPhotoGrid count={18} />
      ) : photos.length === 0 ? (
        <div style={empty}>
          <p style={{ fontSize: 18, marginBottom: 6 }}>No photos in this album</p>
          <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Photos will appear here once they are assigned to this album.</p>
        </div>
      ) : (
        <div style={grid}>
          {photos.map((p) => (
            <PhotoCell
              key={p.id}
              photo={p}
              onClick={() => setSelected(p.id)}
              onSetCover={() => setCover(p.id)}
            />
          ))}
        </div>
      )}

      {selected && <PhotoModal photoId={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function PhotoCell({ photo, onClick, onSetCover }: { photo: PhotoRow; onClick: () => void; onSetCover: () => void }) {
  const [showMenu, setShowMenu] = useState(false);

  return (
    <div
      style={cell}
      onClick={onClick}
      onContextMenu={(e) => { e.preventDefault(); setShowMenu((v) => !v); }}
      onMouseLeave={() => setShowMenu(false)}
    >
      <img src={photo.thumb_url} alt="" style={thumb} loading="lazy" />
      {photo.taken_at && (
        <div style={dateLbl}>{photo.taken_at.slice(0, 10)}</div>
      )}
      {showMenu && (
        <div style={ctxMenu} onClick={(e) => e.stopPropagation()}>
          <button style={ctxBtn} onClick={() => { onSetCover(); setShowMenu(false); }}>
            Set as cover
          </button>
        </div>
      )}
    </div>
  );
}

const h1:         React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const typeBadge:  React.CSSProperties = { background: 'var(--surface2)', color: 'var(--accent)', borderRadius: 6, padding: '3px 10px', fontSize: 12, fontWeight: 600 };
const countBadge: React.CSSProperties = { color: 'var(--text-muted)', fontSize: 14 };
const backBtn:    React.CSSProperties = { background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text)', borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: 13 };
const dangerBtn:  React.CSSProperties = { background: 'var(--danger)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 16px', cursor: 'pointer', fontSize: 13, transition: 'opacity .15s' };
const grid:       React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 6 };
const cell:       React.CSSProperties = { position: 'relative', aspectRatio: '1', borderRadius: 8, overflow: 'hidden', cursor: 'pointer', background: 'var(--surface)' };
const thumb:      React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover', display: 'block', transition: 'transform .15s' };
const dateLbl:    React.CSSProperties = { position: 'absolute', bottom: 0, left: 0, right: 0, background: 'linear-gradient(transparent,rgba(0,0,0,.7))', color: '#ddd', fontSize: 10, padding: '14px 5px 3px', textAlign: 'center', opacity: 0, transition: 'opacity .15s' };
const ctxMenu:    React.CSSProperties = { position: 'absolute', bottom: 6, right: 6, background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8, padding: 4, zIndex: 10, boxShadow: '0 4px 16px rgba(0,0,0,.4)' };
const ctxBtn:     React.CSSProperties = { background: 'none', border: 'none', color: 'var(--text)', padding: '6px 12px', cursor: 'pointer', borderRadius: 6, fontSize: 12, whiteSpace: 'nowrap', width: '100%', textAlign: 'left' };
const empty:      React.CSSProperties = { textAlign: 'center', padding: '60px 20px', color: 'var(--text)' };
