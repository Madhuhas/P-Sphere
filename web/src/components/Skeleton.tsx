export function SkeletonBox({ w = '100%', h = 20, radius = 6 }: { w?: string | number; h?: number; radius?: number }) {
  return <div className="skeleton" style={{ width: w, height: h, borderRadius: radius }} />;
}

export function SkeletonPhotoGrid({ count = 24 }: { count?: number }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 6 }}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton" style={{ aspectRatio: '1', borderRadius: 8 }} />
      ))}
    </div>
  );
}

export function SkeletonAlbumGrid({ count = 8 }: { count?: number }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 16 }}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} style={{ background: 'var(--surface)', borderRadius: 12, overflow: 'hidden' }}>
          <div className="skeleton" style={{ aspectRatio: '4/3' }} />
          <div style={{ padding: '10px 12px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}>
            <SkeletonBox h={14} w="70%" />
            <SkeletonBox h={12} w="40%" />
          </div>
        </div>
      ))}
    </div>
  );
}
