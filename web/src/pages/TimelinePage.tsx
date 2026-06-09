import { useCallback, useEffect, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Photos, PhotoRow } from '../services/api';
import PhotoModal from '../components/PhotoModal';
import { SkeletonPhotoGrid } from '../components/Skeleton';
import { useToast } from '../context/ToastContext';

const COLS        = 5;   // virtualiser works in rows; each row holds COLS photos
const CELL_SIZE   = 168; // px — approximate square cell size
const ROW_GAP     = 6;

export default function TimelinePage() {
  const [photos,   setPhotos]   = useState<PhotoRow[]>([]);
  const [page,     setPage]     = useState(1);
  const [hasMore,  setHasMore]  = useState(true);
  const [loading,  setLoading]  = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [picked,   setPicked]   = useState<Set<string>>(new Set());
  const [selecting, setSelecting] = useState(false);
  const [hovered,  setHovered]  = useState<string | null>(null);
  const { show } = useToast();

  const parentRef = useRef<HTMLDivElement>(null);

  // Group photos into rows for the row virtualiser
  const rows: PhotoRow[][] = [];
  for (let i = 0; i < photos.length; i += COLS) rows.push(photos.slice(i, i + COLS));

  const virtualiser = useVirtualizer({
    count:           rows.length + (hasMore ? 1 : 0), // +1 sentinel row
    getScrollElement: () => parentRef.current,
    estimateSize:    () => CELL_SIZE + ROW_GAP,
    overscan:        4,
  });

  const loadMore = useCallback(async () => {
    if (loading || !hasMore) return;
    setLoading(true);
    try {
      const res = await Photos.timeline(page, 60);
      setPhotos((prev) => [...prev, ...res.data.photos]);
      setHasMore(page < res.data.pagination.pages);
      setPage((p) => p + 1);
    } catch {
      show('Failed to load photos.', 'error');
    } finally {
      setLoading(false);
    }
  }, [loading, hasMore, page, show]);

  // Trigger load when the sentinel row becomes visible
  useEffect(() => {
    const lastItem = virtualiser.getVirtualItems().at(-1);
    if (!lastItem) return;
    if (lastItem.index >= rows.length && hasMore && !loading) loadMore();
  }, [virtualiser.getVirtualItems(), rows.length, hasMore, loading, loadMore]);

  useEffect(() => { loadMore(); }, []); // eslint-disable-line

  function togglePick(id: string) {
    setPicked((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  }
  function handleClick(id: string) { selecting ? togglePick(id) : setSelected(id); }
  function handleRightClick(id: string) { setSelecting(true); setPicked(new Set([id])); show('Selection mode active', 'info'); }

  return (
    <div style={{ padding: '24px 24px 0', display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16, flexShrink: 0 }}>
        <h1 style={h1}>
          {selecting ? `${picked.size} selected` : 'Timeline'}
          <span style={count}>{photos.length} photos</span>
        </h1>
        {selecting && (
          <>
            <button style={pill} onClick={() => show(`${picked.size} photos selected`, 'info')}>Actions</button>
            <button style={{ ...pill, background: 'var(--surface2)' }} onClick={() => { setSelecting(false); setPicked(new Set()); }}>Cancel</button>
          </>
        )}
      </div>

      {/* Virtualised scrollable area */}
      {photos.length === 0 && loading
        ? <SkeletonPhotoGrid count={30} />
        : (
          <div
            ref={parentRef}
            style={{ flex: 1, overflow: 'auto', paddingBottom: 24 }}
          >
            <div style={{ height: virtualiser.getTotalSize(), position: 'relative' }}>
              {virtualiser.getVirtualItems().map((vRow) => {
                const rowPhotos = rows[vRow.index];
                if (!rowPhotos) {
                  // Sentinel / loading row
                  return (
                    <div key="sentinel" style={{ position: 'absolute', top: vRow.start, height: vRow.size, width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      {loading && <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>Loading…</span>}
                      {!hasMore && <span style={{ color: 'var(--border)', fontSize: 13 }}>All caught up</span>}
                    </div>
                  );
                }
                return (
                  <div
                    key={vRow.index}
                    style={{ position: 'absolute', top: vRow.start, width: '100%', display: 'grid', gridTemplateColumns: `repeat(${COLS}, 1fr)`, gap: ROW_GAP }}
                  >
                    {rowPhotos.map((p) => {
                      const isPicked  = picked.has(p.id);
                      const isHovered = hovered === p.id;
                      return (
                        <div
                          key={p.id}
                          style={{ ...cell, outline: isPicked ? '3px solid var(--accent)' : isHovered ? '2px solid var(--accent)' : 'none', transform: isHovered && !selecting ? 'scale(1.03)' : 'scale(1)' }}
                          onClick={() => handleClick(p.id)}
                          onMouseEnter={() => setHovered(p.id)}
                          onMouseLeave={() => setHovered(null)}
                          onContextMenu={(e) => { e.preventDefault(); handleRightClick(p.id); }}
                        >
                          <img src={p.thumb_url} alt="" style={thumb} loading="lazy" />
                          {selecting && (
                            <div style={{ ...check, background: isPicked ? 'var(--accent)' : 'rgba(0,0,0,.5)', border: isPicked ? 'none' : '2px solid #fff' }}>
                              {isPicked && <span style={{ color: '#fff', fontSize: 11, fontWeight: 700 }}>✓</span>}
                            </div>
                          )}
                          {p.taken_at && (
                            <div style={{ ...dateLbl, opacity: isHovered || isPicked ? 1 : 0 }}>
                              {p.taken_at.slice(0, 10)}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          </div>
        )
      }

      {selected && <PhotoModal photoId={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

const h1:      React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const count:   React.CSSProperties = { fontSize: 14, color: 'var(--text-muted)', fontWeight: 400, marginLeft: 10 };
const cell:    React.CSSProperties = { position: 'relative', cursor: 'pointer', borderRadius: 8, overflow: 'hidden', aspectRatio: '1', background: 'var(--surface)', transition: 'transform .15s, outline .1s' };
const thumb:   React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover', display: 'block' };
const dateLbl: React.CSSProperties = { position: 'absolute', bottom: 0, left: 0, right: 0, background: 'linear-gradient(transparent,rgba(0,0,0,.7))', color: '#ddd', fontSize: 10, padding: '14px 5px 3px', textAlign: 'center', transition: 'opacity .15s' };
const check:   React.CSSProperties = { position: 'absolute', top: 6, left: 6, width: 20, height: 20, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', transition: 'background .15s' };
const pill:    React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 20, padding: '6px 16px', cursor: 'pointer', fontSize: 13 };
