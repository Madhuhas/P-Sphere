import exifr from 'exifr';
import { pool } from '../db/pool';
import { generateThumbnails } from './thumbnail.service';
import { computeDHash, storeDHash } from './perceptualHash.service';
import { enqueuePhotoProcess } from '../queue/producer';

interface ExifResult {
  takenAt: Date | null;
  width: number | null;
  height: number | null;
  camera: string | null;
  lat: number | null;
  lon: number | null;
  raw: Record<string, unknown>;
}

async function parseExif(filePath: string): Promise<ExifResult> {
  try {
    const data = await exifr.parse(filePath, {
      tiff: true,
      exif: true,
      gps: true,
      ifd1: false,
    }) as Record<string, unknown> | null;

    if (!data) return { takenAt: null, width: null, height: null, camera: null, lat: null, lon: null, raw: {} };

    const takenAt =
      (data['DateTimeOriginal'] as Date | undefined) ??
      (data['CreateDate'] as Date | undefined) ??
      null;

    const make  = (data['Make']  as string | undefined)?.trim() ?? null;
    const model = (data['Model'] as string | undefined)?.trim() ?? null;
    const camera = make && model ? `${make} ${model}` : (model ?? make ?? null);

    const lat = typeof data['latitude']  === 'number' ? data['latitude']  as number : null;
    const lon = typeof data['longitude'] === 'number' ? data['longitude'] as number : null;

    const width  = (data['ImageWidth']  as number | undefined) ?? (data['ExifImageWidth']  as number | undefined) ?? null;
    const height = (data['ImageHeight'] as number | undefined) ?? (data['ExifImageHeight'] as number | undefined) ?? null;

    return { takenAt, width, height, camera, lat, lon, raw: data };
  } catch {
    return { takenAt: null, width: null, height: null, camera: null, lat: null, lon: null, raw: {} };
  }
}

export async function ingestPhoto(fileId: string, filePath: string, userId?: string): Promise<string> {
  const exif = await parseExif(filePath);

  const { rows } = await pool.query<{ id: string }>(
    `INSERT INTO photos (file_id, taken_at, width, height, camera, exif_json)
     VALUES ($1, $2, $3, $4, $5, $6)
     ON CONFLICT (file_id) DO UPDATE SET
       taken_at  = EXCLUDED.taken_at,
       width     = EXCLUDED.width,
       height    = EXCLUDED.height,
       camera    = EXCLUDED.camera,
       exif_json = EXCLUDED.exif_json
     RETURNING id`,
    [fileId, exif.takenAt, exif.width, exif.height, exif.camera, JSON.stringify(exif.raw)]
  );

  const photoId = rows[0]!.id;

  if (exif.lat !== null && exif.lon !== null) {
    await pool.query(
      `INSERT INTO photo_locations (photo_id, lat, lon, source)
       VALUES ($1, $2, $3, 'gps')
       ON CONFLICT (photo_id) DO UPDATE SET
         lat    = EXCLUDED.lat,
         lon    = EXCLUDED.lon,
         source = 'gps'`,
      [photoId, exif.lat, exif.lon]
    );
  }

  // Generate 256px + 1024px WebP thumbnails (errors are non-fatal)
  await generateThumbnails(photoId, filePath).catch((err: unknown) => {
    console.error('Thumbnail generation failed for photo', photoId, err);
  });

  // Compute perceptual hash for duplicate detection
  await computeDHash(filePath)
    .then((hash) => storeDHash(photoId, hash))
    .catch((err: unknown) => {
      console.error('Perceptual hash failed for photo', photoId, err);
    });

  // Enqueue vision AI processing job
  if (userId) {
    await enqueuePhotoProcess({ photoId, fileId, filePath, userId }).catch((err: unknown) => {
      console.error('Failed to enqueue photo.process job:', err);
    });
  }

  return photoId;
}

/**
 * Process multiple photos concurrently with a capped concurrency limit.
 * Used by bulk-import flows or re-processing jobs.
 */
export async function batchIngestPhotos(
  items: { fileId: string; filePath: string; userId: string }[],
  concurrency = 4
): Promise<{ succeeded: number; failed: number }> {
  let succeeded = 0;
  let failed    = 0;
  // Copy to avoid mutating the caller's array; shift() is safe in single-threaded JS
  const queue = [...items];

  async function worker() {
    let item: typeof queue[0] | undefined;
    while ((item = queue.shift()) !== undefined) {
      try {
        await ingestPhoto(item.fileId, item.filePath, item.userId);
        succeeded++;
      } catch (err) {
        console.error('[photo] ingestPhoto failed for', item.fileId, err instanceof Error ? err.message : err);
        failed++;
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, worker));
  return { succeeded, failed };
}
