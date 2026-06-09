import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { pool } from '../db/pool';
import {
  generateAllSmartAlbums,
  generateTripAlbums,
  generatePeopleAlbums,
  generatePetAlbums,
  generatePlaceAlbums,
} from '../services/smartAlbums.service';
import { AuthRequest } from '../types';

const router = Router();
router.use(requireAuth);

// GET /api/albums
router.get('/', async (req: AuthRequest, res: Response) => {
  const { rows } = await pool.query(
    `SELECT
       a.id, a.name, a.type, a.created_at,
       COUNT(ai.photo_id)::int AS photo_count,
       '/api/photos/thumb/' || MIN(ai.photo_id)::text || '/sm' AS cover_url
     FROM albums a
     LEFT JOIN album_items ai ON ai.album_id = a.id
     WHERE a.user_id = $1
     GROUP BY a.id
     ORDER BY a.created_at DESC`,
    [req.user!.userId]
  );
  res.json({ albums: rows });
});

// GET /api/albums/:id
router.get('/:id', async (req: AuthRequest, res: Response) => {
  const { rows: albumRows } = await pool.query(
    'SELECT * FROM albums WHERE id = $1 AND user_id = $2',
    [req.params['id'], req.user!.userId]
  );
  if (!albumRows[0]) { res.status(404).json({ error: 'Album not found' }); return; }

  const { rows: photos } = await pool.query(
    `SELECT p.id, p.taken_at, p.width, p.height,
            '/api/photos/thumb/' || p.id || '/sm' AS thumb_url
     FROM album_items ai
     JOIN photos p ON p.id = ai.photo_id
     WHERE ai.album_id = $1
     ORDER BY p.taken_at ASC NULLS LAST`,
    [req.params['id']]
  );
  res.json({ album: albumRows[0], photos });
});

// DELETE /api/albums/:id
router.delete('/:id', async (req: AuthRequest, res: Response) => {
  const { rowCount } = await pool.query(
    'DELETE FROM albums WHERE id = $1 AND user_id = $2',
    [req.params['id'], req.user!.userId]
  );
  if (!rowCount) { res.status(404).json({ error: 'Album not found' }); return; }
  res.json({ message: 'Album deleted' });
});

// PATCH /api/albums/:id/cover — set cover photo
router.patch('/:id/cover', async (req: AuthRequest, res: Response) => {
  const { photoId } = req.body as { photoId?: string };
  if (!photoId) { res.status(400).json({ error: 'photoId is required' }); return; }

  // Verify photo belongs to this album and user
  const { rows } = await pool.query(
    `SELECT ai.photo_id FROM album_items ai
     JOIN albums a ON a.id = ai.album_id
     JOIN photos p ON p.id = ai.photo_id
     JOIN files  f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE a.id = $1 AND a.user_id = $2 AND ai.photo_id = $3`,
    [req.params['id'], req.user!.userId, photoId]
  );
  if (!rows[0]) { res.status(404).json({ error: 'Photo not in this album' }); return; }

  await pool.query(
    `UPDATE albums SET summary = $1 WHERE id = $2 AND user_id = $3`,
    [`cover:${photoId}`, req.params['id'], req.user!.userId]
  );
  res.json({ message: 'Cover updated', photoId });
});

// POST /api/albums/smart/generate  — run all four generators
router.post('/smart/generate', async (req: AuthRequest, res: Response) => {
  try {
    const result = await generateAllSmartAlbums(req.user!.userId);
    res.status(201).json(result);
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Smart album generation failed' });
  }
});

// POST /api/albums/smart/trips
router.post('/smart/trips', async (req: AuthRequest, res: Response) => {
  try { res.status(201).json({ trips: await generateTripAlbums(req.user!.userId) }); }
  catch (err: unknown) { res.status(500).json({ error: err instanceof Error ? err.message : 'Failed' }); }
});

// POST /api/albums/smart/people
router.post('/smart/people', async (req: AuthRequest, res: Response) => {
  try { res.status(201).json({ people: await generatePeopleAlbums(req.user!.userId) }); }
  catch (err: unknown) { res.status(500).json({ error: err instanceof Error ? err.message : 'Failed' }); }
});

// POST /api/albums/smart/pets
router.post('/smart/pets', async (req: AuthRequest, res: Response) => {
  try { res.status(201).json({ pets: await generatePetAlbums(req.user!.userId) }); }
  catch (err: unknown) { res.status(500).json({ error: err instanceof Error ? err.message : 'Failed' }); }
});

// POST /api/albums/smart/places
router.post('/smart/places', async (req: AuthRequest, res: Response) => {
  try { res.status(201).json({ places: await generatePlaceAlbums(req.user!.userId) }); }
  catch (err: unknown) { res.status(500).json({ error: err instanceof Error ? err.message : 'Failed' }); }
});

export default router;
