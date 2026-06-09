import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { pool } from '../db/pool';
import { AuthRequest } from '../types';

const router = Router();
router.use(requireAuth);

// GET /api/stats/storage
router.get('/storage', async (req: AuthRequest, res: Response) => {
  const { rows: devices } = await pool.query(
    `SELECT
       d.id, d.name, d.type,
       COUNT(f.id)::int       AS file_count,
       COALESCE(SUM(f.size), 0)::bigint AS total_bytes
     FROM devices d
     LEFT JOIN files f ON f.device_id = d.id
     WHERE d.user_id = $1
     GROUP BY d.id, d.name, d.type
     ORDER BY total_bytes DESC`,
    [req.user!.userId]
  );

  const { rows: totals } = await pool.query(
    `SELECT
       COUNT(f.id)::int             AS total_files,
       COALESCE(SUM(f.size), 0)::bigint AS total_bytes,
       COUNT(CASE WHEN f.is_photo THEN 1 END)::int AS photo_count
     FROM files f
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1`,
    [req.user!.userId]
  );

  res.json({ devices, totals: totals[0] });
});

// GET /api/stats/sync-history?limit=20
router.get('/sync-history', async (req: AuthRequest, res: Response) => {
  const limit = Math.min(50, parseInt(String(req.query['limit'] ?? '20'), 10));

  const { rows } = await pool.query(
    `SELECT
       d.name        AS device_name,
       d.type        AS device_type,
       DATE_TRUNC('day', f.synced_at)::date::text AS sync_date,
       COUNT(*)::int                               AS file_count,
       COALESCE(SUM(f.size), 0)::bigint            AS bytes_synced,
       COUNT(CASE WHEN f.is_photo THEN 1 END)::int AS photo_count
     FROM files f
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1 AND f.synced_at IS NOT NULL
     GROUP BY d.name, d.type, DATE_TRUNC('day', f.synced_at)
     ORDER BY sync_date DESC, bytes_synced DESC
     LIMIT $2`,
    [req.user!.userId, limit]
  );

  res.json({ history: rows });
});

export default router;
