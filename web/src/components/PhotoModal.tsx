import { useEffect, useState } from 'react';
import { Photos, PhotoDetail } from '../services/api';
import { SkeletonBox } from './Skeleton';

export default function PhotoModal({ photoId, onClose }: { photoId: string; onClose: () => void }) {
  const [photo, setPhoto] = useState<PhotoDetail | null>(null);

  useEffect(() => {
    Photos.get(photoId).then((r) => setPhoto(r.data.photo)).catch(console.error);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [photoId, onClose]);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="page-enter" style={modal} onClick={(e) => e.stopPropagation()}>
        <button style={closeBtn} onClick={onClose}>✕</button>

        {photo ? (
          <>
            <img
              src={photo.thumb_url.replace('/sm', '/lg')}
              alt=""
              style={{ maxWidth: '100%', maxHeight: '68vh', objectFit: 'contain', borderRadius: 8 }}
            />
            <div style={meta}>
              {photo.taken_at && <Meta label="Date"     value={photo.taken_at.slice(0, 10)} />}
              {photo.camera   && <Meta label="Camera"   value={photo.camera} />}
              {(photo.city || photo.country) &&
                <Meta label="Location" value={[photo.city, photo.country].filter(Boolean).join(', ')} />}
              {photo.tags?.length   ? <Meta label="Tags"   value={photo.tags.join(', ')} />   : null}
              {photo.people?.length ? <Meta label="People" value={photo.people.join(', ')} /> : null}
              {photo.width && photo.height &&
                <Meta label="Size" value={`${photo.width} × ${photo.height}`} />}
            </div>
          </>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <SkeletonBox h={300} />
            <SkeletonBox h={14} w="60%" />
            <SkeletonBox h={14} w="40%" />
          </div>
        )}
      </div>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', gap: 10, fontSize: 13 }}>
      <span style={{ color: 'var(--text-muted)', minWidth: 72 }}>{label}</span>
      <span style={{ color: 'var(--text)' }}>{value}</span>
    </div>
  );
}

const overlay:  React.CSSProperties = { position: 'fixed', inset: 0, background: 'var(--overlay)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, backdropFilter: 'blur(4px)' };
const modal:    React.CSSProperties = { background: 'var(--surface)', borderRadius: 16, padding: 24, maxWidth: 800, width: '95vw', position: 'relative', display: 'flex', flexDirection: 'column', gap: 16, maxHeight: '90vh', overflowY: 'auto' };
const meta:     React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 7 };
const closeBtn: React.CSSProperties = { position: 'absolute', top: 12, right: 14, background: 'none', border: 'none', color: 'var(--text-muted)', fontSize: 20, cursor: 'pointer', lineHeight: 1 };
