import path from 'path';
import sharp from 'sharp';
import { thumbsDir } from '../config/storage';
import { pool } from '../db/pool';

const SIZES = [
  { key: 'sm' as const, px: 256  },
  { key: 'lg' as const, px: 1024 },
];

export interface ThumbPaths {
  sm: string | null;
  lg: string | null;
}

export async function generateThumbnails(photoId: string, sourcePath: string): Promise<ThumbPaths> {
  const dir = thumbsDir();
  const paths: ThumbPaths = { sm: null, lg: null };

  // Use allSettled so a single failure doesn't discard the other thumbnail
  const results = await Promise.allSettled(
    SIZES.map(async ({ key, px }) => {
      const filename = `${photoId}_${px}.webp`;
      const dest = path.join(dir, filename);

      await sharp(sourcePath)
        .rotate()
        .resize(px, px, { fit: 'inside', withoutEnlargement: true })
        .webp({ quality: 80 })
        .toFile(dest);

      return { key, dest };
    })
  );

  for (const result of results) {
    if (result.status === 'fulfilled') {
      paths[result.value.key] = result.value.dest;
    } else {
      console.error('[thumbnail] Failed to generate thumbnail:', result.reason);
    }
  }

  // Only update columns for thumbnails that were successfully generated
  if (paths.sm || paths.lg) {
    await pool.query(
      `UPDATE photos
       SET thumb_sm = COALESCE($1, thumb_sm),
           thumb_lg = COALESCE($2, thumb_lg)
       WHERE id = $3`,
      [paths.sm, paths.lg, photoId]
    );
  }

  return paths;
}
