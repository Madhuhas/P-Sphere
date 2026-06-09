import { Router, Response } from 'express';
import fs from 'fs';
import path from 'path';
import { requireAuth } from '../middleware/auth';
import { checkHashes, upsertFile } from '../services/sync.service';
import { upload, filesDir } from '../config/storage';
import { pool } from '../db/pool';
import { cacheDel } from '../utils/cache';
import { enqueueExifBatch } from '../queue/producer';
import { AuthRequest } from '../types';

const router = Router();

router.use(requireAuth);

// POST /api/sync/check
router.post('/check', async (req: AuthRequest, res: Response) => {
  const { deviceToken, hashes } = req.body as { deviceToken?: string; hashes?: string[] };

  if (!deviceToken || !Array.isArray(hashes)) {
    res.status(400).json({ error: 'deviceToken and hashes[] are required' });
    return;
  }

  const { rows } = await pool.query<{ id: string }>(
    'SELECT id FROM devices WHERE device_token = $1 AND user_id = $2',
    [deviceToken, req.user!.userId]
  );

  if (!rows[0]) {
    res.status(403).json({ error: 'Unknown device token' });
    return;
  }

  // Fire-and-forget last_seen update
  pool.query('UPDATE devices SET last_seen = NOW() WHERE id = $1', [rows[0].id]).catch((err) => {
    console.warn('[sync] Failed to update last_seen:', err.message);
  });

  try {
    const missing = await checkHashes(rows[0].id, hashes);
    res.json({ missing });
  } catch {
    res.status(500).json({ error: 'Hash check failed' });
  }
});

// POST /api/sync/upload
// Multipart fields: file (binary), deviceToken, path, hash, modifiedAt
router.post(
  '/upload',
  upload.single('file'),
  async (req: AuthRequest, res: Response) => {
    const { deviceToken, path: filePath, hash, modifiedAt } = req.body as {
      deviceToken?: string;
      path?: string;
      hash?: string;
      modifiedAt?: string;
    };

    if (!req.file || !deviceToken || !filePath || !hash || !modifiedAt) {
      if (req.file) await fs.promises.unlink(req.file.path).catch(() => {});
      res.status(400).json({ error: 'file, deviceToken, path, hash, and modifiedAt are required' });
      return;
    }

    // Reject path traversal and absolute paths
    if (/\.\./.test(filePath) || path.isAbsolute(filePath)) {
      await fs.promises.unlink(req.file.path).catch(() => {});
      res.status(400).json({ error: 'Invalid file path' });
      return;
    }

    const { rows } = await pool.query<{ id: string }>(
      'SELECT id FROM devices WHERE device_token = $1 AND user_id = $2',
      [deviceToken, req.user!.userId]
    );

    if (!rows[0]) {
      await fs.promises.unlink(req.file.path).catch(() => {});
      res.status(403).json({ error: 'Unknown device token' });
      return;
    }

    const deviceId = rows[0].id;
    const mimeType = req.file.mimetype;
    const isPhoto  = mimeType.startsWith('image/');

    const destDir  = filesDir(req.user!.userId, deviceId);
    const destPath = path.join(destDir, path.basename(filePath));

    try {
      await fs.promises.mkdir(path.dirname(destPath), { recursive: true });
      await fs.promises.rename(req.file.path, destPath);
    } catch (err) {
      await fs.promises.unlink(req.file.path).catch(() => {});
      console.error('[sync] File move failed:', err);
      res.status(500).json({ error: 'Failed to store file' });
      return;
    }

    try {
      await upsertFile(deviceId, {
        path: filePath,
        hash,
        size: req.file.size,
        mimeType,
        isPhoto,
        modifiedAt,
      });

      const { rows: fileRows } = await pool.query<{ id: string }>(
        'SELECT id FROM files WHERE device_id = $1 AND path = $2',
        [deviceId, filePath]
      );

      const fileId = fileRows[0]?.id;
      res.status(201).json({ fileId });

      // Enqueue EXIF extraction as a batch job — non-blocking
      if (isPhoto && fileId) {
        enqueueExifBatch([{ fileId, filePath: destPath, userId: req.user!.userId }]).catch((err) => {
          console.warn('[sync] Failed to enqueue EXIF job (photo stored, processing delayed):', err.message);
        });
      }

      // Invalidate timeline cache for this user
      cacheDel(`timeline:${req.user!.userId}:*`).catch(() => {});
    } catch (err) {
      console.error('[sync] Upload DB write failed:', err);
      res.status(500).json({ error: 'Upload failed' });
    }
  }
);

// GET /api/sync/conflicts
router.get('/conflicts', async (req: AuthRequest, res: Response) => {
  try {
    const { rows: detected } = await pool.query(
      `SELECT
         fa.path,
         da.id AS device_a_id, da.name AS device_a_name,
         fa.id AS file_a_id,   fa.hash AS hash_a, fa.modified_at AS modified_a,
         db.id AS device_b_id, db.name AS device_b_name,
         fb.id AS file_b_id,   fb.hash AS hash_b, fb.modified_at AS modified_b
       FROM files fa
       JOIN devices da ON da.id = fa.device_id
       JOIN files fb   ON fb.path = fa.path AND fb.device_id != fa.device_id AND fb.hash != fa.hash
       JOIN devices db ON db.id = fb.device_id
       WHERE da.user_id = $1 AND db.user_id = $1
         AND fa.id < fb.id
       ORDER BY fa.modified_at DESC
       LIMIT 50`,
      [req.user!.userId]
    );

    for (const row of detected) {
      await pool.query(
        `INSERT INTO file_conflicts (user_id, path, file_id_a, file_id_b)
         VALUES ($1, $2, $3, $4) ON CONFLICT (file_id_a, file_id_b) DO NOTHING`,
        [req.user!.userId, row.path, row.file_a_id, row.file_b_id]
      );
    }

    const { rows: conflicts } = await pool.query(
      `SELECT
         fc.id, fc.path, fc.created_at,
         da.name AS device_a_name, fa.modified_at AS modified_a, fa.id AS file_a_id,
         db.name AS device_b_name, fb.modified_at AS modified_b, fb.id AS file_b_id
       FROM file_conflicts fc
       JOIN files   fa ON fa.id = fc.file_id_a
       JOIN files   fb ON fb.id = fc.file_id_b
       JOIN devices da ON da.id = fa.device_id
       JOIN devices db ON db.id = fb.device_id
       WHERE fc.user_id = $1 AND fc.resolved_at IS NULL
       ORDER BY fc.created_at DESC`,
      [req.user!.userId]
    );

    res.json({ conflicts, total: conflicts.length });
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Conflict detection failed' });
  }
});

// POST /api/sync/conflicts/:id/resolve
router.post('/conflicts/:id/resolve', async (req: AuthRequest, res: Response) => {
  const { keepFileId } = req.body as { keepFileId?: string };
  if (!keepFileId) { res.status(400).json({ error: 'keepFileId is required' }); return; }

  const { rows } = await pool.query(
    `UPDATE file_conflicts
     SET resolved_at = NOW(), kept_file_id = $1
     WHERE id = $2 AND user_id = $3 AND resolved_at IS NULL
     RETURNING id`,
    [keepFileId, req.params['id'], req.user!.userId]
  );

  if (!rows[0]) { res.status(404).json({ error: 'Conflict not found or already resolved' }); return; }
  res.json({ message: 'Conflict resolved', kept: keepFileId });
});

export default router;
