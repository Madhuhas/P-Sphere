import { Router, Response } from 'express';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { requireAuth } from '../middleware/auth';
import { pool } from '../db/pool';
import { findDuplicates } from '../services/perceptualHash.service';
import { cacheGet, cacheSet } from '../utils/cache';
import { thumbsDir } from '../config/storage';
import { AuthRequest } from '../types';

const TIMELINE_TTL = 60;        // seconds

const router = Router();

router.use(requireAuth);

// ─── GET /api/photos/timeline ────────────────────────────────────────────────
// Query: page, limit (max 200), from (ISO), to (ISO)
router.get('/timeline', async (req: AuthRequest, res: Response) => {
  const page  = Math.max(1, parseInt(String(req.query['page']  ?? '1'),  10));
  const limit = Math.min(200, Math.max(1, parseInt(String(req.query['limit'] ?? '50'), 10)));
  const from  = req.query['from'] as string | undefined;
  const to    = req.query['to']   as string | undefined;
  const offset = (page - 1) * limit;

  const cacheKey = `timeline:${req.user!.userId}:${page}:${limit}:${from ?? ''}:${to ?? ''}`;
  const cached = await cacheGet<unknown>(cacheKey);
  if (cached) { res.json(cached); return; }

  // Build separate param arrays so placeholder indices are correct for each query.
  // Main query has 3 leading params (userId, limit, offset); count query has 1 (userId).
  const mainParams: unknown[]  = [req.user!.userId, limit, offset];
  const countParams: unknown[] = [req.user!.userId];
  const mainConditions:  string[] = [];
  const countConditions: string[] = [];

  if (from) {
    mainParams.push(from);
    countParams.push(from);
    mainConditions.push(`COALESCE(p.taken_at, f.modified_at) >= $${mainParams.length}`);
    countConditions.push(`COALESCE(p.taken_at, f.modified_at) >= $${countParams.length}`);
  }
  if (to) {
    mainParams.push(to);
    countParams.push(to);
    mainConditions.push(`COALESCE(p.taken_at, f.modified_at) <= $${mainParams.length}`);
    countConditions.push(`COALESCE(p.taken_at, f.modified_at) <= $${countParams.length}`);
  }

  const mainWhere  = mainConditions.length  ? `AND ${mainConditions.join(' AND ')}`  : '';
  const countWhere = countConditions.length ? `AND ${countConditions.join(' AND ')}` : '';

  const [{ rows }, { rows: countRows }] = await Promise.all([
    pool.query(
      `SELECT
         p.id,
         COALESCE(p.taken_at, f.modified_at) AS taken_at,
         p.width, p.height, p.camera,
         p.thumb_sm IS NOT NULL AS has_thumb,
         '/api/photos/thumb/' || p.id || '/sm' AS thumb_url,
         '/api/photos/thumb/' || p.id || '/lg' AS thumb_lg_url,
         f.path, f.size, f.mime_type
       FROM photos p
       JOIN files f ON f.id = p.file_id
       JOIN devices d ON d.id = f.device_id
       WHERE d.user_id = $1 ${mainWhere}
       ORDER BY COALESCE(p.taken_at, f.modified_at) DESC NULLS LAST
       LIMIT $2 OFFSET $3`,
      mainParams
    ),
    pool.query<{ total: string }>(
      `SELECT COUNT(*) AS total
       FROM photos p
       JOIN files f ON f.id = p.file_id
       JOIN devices d ON d.id = f.device_id
       WHERE d.user_id = $1 ${countWhere}`,
      countParams
    ),
  ]);

  const payload = {
    photos: rows,
    pagination: {
      page,
      limit,
      total: parseInt(countRows[0]?.total ?? '0', 10),
      pages: Math.ceil(parseInt(countRows[0]?.total ?? '0', 10) / limit),
    },
  };
  await cacheSet(cacheKey, payload, TIMELINE_TTL);
  res.json({
    ...payload,
  });
});

// ─── DELETE /api/photos/batch ────────────────────────────────────────────────
// Body: { photoIds: string[] }
router.delete('/batch', async (req: AuthRequest, res: Response) => {
  const { photoIds } = req.body as { photoIds?: string[] };
  if (!Array.isArray(photoIds) || photoIds.length === 0) {
    res.status(400).json({ error: 'photoIds[] is required' }); return;
  }

  // Only delete photos belonging to this user
  const { rowCount } = await pool.query(
    `DELETE FROM files
     WHERE id IN (
       SELECT p.file_id FROM photos p
       JOIN files   f ON f.id  = p.file_id
       JOIN devices d ON d.id  = f.device_id
       WHERE p.id = ANY($1::uuid[]) AND d.user_id = $2
     )`,
    [photoIds, req.user!.userId]
  );

  res.json({ deleted: rowCount ?? 0 });
});

// ─── GET /api/photos/duplicates ──────────────────────────────────────────────
// Returns groups of near-duplicate photos sorted by similarity score desc.
router.get('/duplicates', async (req: AuthRequest, res: Response) => {
  try {
    const groups = await findDuplicates(req.user!.userId);
    res.json({ groups, total: groups.length });
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Duplicate detection failed' });
  }
});

// ─── GET /api/photos/locations ───────────────────────────────────────────────
// Returns GPS clusters for map view.
// Query: precision (1-4, default 2 ≈ ~1 km grid), viewport bounds (minLat/maxLat/minLon/maxLon)
router.get('/locations', async (req: AuthRequest, res: Response) => {
  const rawPrec = parseInt(String(req.query['precision'] ?? '2'), 10);
  const precision = Math.min(4, Math.max(1, isNaN(rawPrec) ? 2 : rawPrec));

  const minLat = parseFloat(String(req.query['minLat'] ?? ''));
  const maxLat = parseFloat(String(req.query['maxLat'] ?? ''));
  const minLon = parseFloat(String(req.query['minLon'] ?? ''));
  const maxLon = parseFloat(String(req.query['maxLon'] ?? ''));

  const params: unknown[] = [req.user!.userId, precision];
  const bounds: string[] = [];
  if (!isNaN(minLat)) { params.push(minLat); bounds.push(`pl.lat >= $${params.length}`); }
  if (!isNaN(maxLat)) { params.push(maxLat); bounds.push(`pl.lat <= $${params.length}`); }
  if (!isNaN(minLon)) { params.push(minLon); bounds.push(`pl.lon >= $${params.length}`); }
  if (!isNaN(maxLon)) { params.push(maxLon); bounds.push(`pl.lon <= $${params.length}`); }
  const boundsClause = bounds.length ? `AND ${bounds.join(' AND ')}` : '';

  try {
    const { rows } = await pool.query(
      `SELECT
         ROUND(pl.lat::numeric, $2)       AS lat,
         ROUND(pl.lon::numeric, $2)       AS lon,
         AVG(pl.lat)                      AS center_lat,
         AVG(pl.lon)                      AS center_lon,
         COUNT(*)::int                    AS count,
         MIN(p.id)                        AS cover_photo_id,
         '/api/photos/thumb/' || MIN(p.id) || '/sm' AS cover_thumb_url,
         MIN(COALESCE(p.taken_at, f.modified_at)) AS earliest,
         MAX(COALESCE(p.taken_at, f.modified_at)) AS latest
       FROM photo_locations pl
       JOIN photos  p ON p.id  = pl.photo_id
       JOIN files   f ON f.id  = p.file_id
       JOIN devices d ON d.id  = f.device_id
       WHERE d.user_id = $1
         AND pl.lat IS NOT NULL
         AND pl.lon IS NOT NULL
         ${boundsClause}
       GROUP BY ROUND(pl.lat::numeric, $2), ROUND(pl.lon::numeric, $2)
       ORDER BY count DESC`,
      params
    );

    res.json({ locations: rows, precision });
  } catch {
    res.status(500).json({ error: 'Failed to fetch locations' });
  }
});

// ─── GET /api/photos/thumb/:photoId/:size ────────────────────────────────────
router.get('/thumb/:photoId/:size', async (req: AuthRequest, res: Response) => {
  const { photoId, size } = req.params;
  if (size !== 'sm' && size !== 'lg') {
    res.status(400).json({ error: 'size must be sm or lg' });
    return;
  }

  const col = size === 'sm' ? 'thumb_sm' : 'thumb_lg';
  const { rows } = await pool.query<{ path: string }>(
    `SELECT ${col} AS path FROM photos
     JOIN files   ON files.id   = photos.file_id
     JOIN devices ON devices.id = files.device_id
     WHERE photos.id = $1 AND devices.user_id = $2`,
    [photoId, req.user!.userId]
  );

  const thumbPath = rows[0]?.path;

  // Guard against a compromised DB record pointing outside the thumbnails directory
  const thumbsBase = thumbsDir();
  if (!thumbPath || !thumbPath.startsWith(thumbsBase)) {
    res.status(404).json({ error: 'Thumbnail not found' });
    return;
  }

  // CDN-ready headers: immutable 1-year cache + ETag for 304 support
  let stat: fs.Stats;
  try {
    stat = await fs.promises.stat(thumbPath);
  } catch {
    res.status(404).json({ error: 'Thumbnail not found' });
    return;
  }

  const etag = crypto
    .createHash('md5')
    .update(`${thumbPath}:${stat.mtimeMs}:${stat.ino}`)
    .digest('hex');

  if (req.headers['if-none-match'] === etag) {
    res.status(304).end();
    return;
  }

  res.set({
    'Cache-Control': 'public, max-age=31536000, immutable',
    'ETag':          etag,
    'Content-Type':  'image/webp',
  });
  res.sendFile(thumbPath);
});

// ─── GET /api/photos/:id — must be last (catches any UUID) ───────────────────
router.get('/:id', async (req: AuthRequest, res: Response) => {
  const { rows } = await pool.query(
    `SELECT
       p.id, p.taken_at, p.width, p.height, p.camera,
       p.exif_json AS exif,
       '/api/photos/thumb/' || p.id || '/sm' AS thumb_url,
       '/api/photos/thumb/' || p.id || '/lg' AS thumb_lg_url,
       f.path, f.size, f.mime_type,
       pl.lat, pl.lon, pl.city, pl.country, pl.source AS location_source
     FROM photos p
     JOIN files             f  ON f.id  = p.file_id
     JOIN devices           d  ON d.id  = f.device_id
     LEFT JOIN photo_locations pl ON pl.photo_id = p.id
     WHERE p.id = $1 AND d.user_id = $2`,
    [req.params['id'], req.user!.userId]
  );

  if (!rows[0]) { res.status(404).json({ error: 'Photo not found' }); return; }
  res.json({ photo: rows[0] });
});

export default router;
