import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { pool } from '../db/pool';
import { AuthRequest } from '../types';

const router = Router();
router.use(requireAuth);

// GET /api/faces/clusters
router.get('/clusters', async (req: AuthRequest, res: Response) => {
  const { rows } = await pool.query(
    `SELECT
       fc.id, fc.label, fc.person_name, fc.confidence,
       COUNT(fa.id)::int AS face_count,
       COUNT(DISTINCT fa.photo_id)::int AS photo_count,
       '/api/photos/thumb/' || MIN(fa.photo_id)::text || '/sm' AS cover_url
     FROM face_clusters fc
     LEFT JOIN faces fa ON fa.cluster_id = fc.id
     WHERE fc.user_id = $1
     GROUP BY fc.id
     ORDER BY photo_count DESC`,
    [req.user!.userId]
  );
  res.json({ clusters: rows });
});

// PATCH /api/faces/clusters/:id  — rename
router.patch('/clusters/:id', async (req: AuthRequest, res: Response) => {
  const { personName, label } = req.body as { personName?: string; label?: string };

  const { rows } = await pool.query(
    `UPDATE face_clusters
     SET person_name = COALESCE($1, person_name),
         label       = COALESCE($2, label)
     WHERE id = $3 AND user_id = $4
     RETURNING *`,
    [personName ?? null, label ?? null, req.params['id'], req.user!.userId]
  );

  if (!rows[0]) { res.status(404).json({ error: 'Cluster not found' }); return; }
  res.json({ cluster: rows[0] });
});

// DELETE /api/faces/clusters/:id
router.delete('/clusters/:id', async (req: AuthRequest, res: Response) => {
  // Unlink faces first, then delete cluster
  await pool.query(
    `UPDATE faces SET cluster_id = NULL
     WHERE cluster_id = $1
       AND photo_id IN (
         SELECT p.id FROM photos p
         JOIN files f ON f.id = p.file_id
         JOIN devices d ON d.id = f.device_id
         WHERE d.user_id = $2
       )`,
    [req.params['id'], req.user!.userId]
  );

  const { rowCount } = await pool.query(
    'DELETE FROM face_clusters WHERE id = $1 AND user_id = $2',
    [req.params['id'], req.user!.userId]
  );

  if (!rowCount) { res.status(404).json({ error: 'Cluster not found' }); return; }
  res.json({ message: 'Cluster deleted' });
});

export default router;
