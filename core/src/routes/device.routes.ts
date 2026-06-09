import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { registerDevice, getUserDevices, removeDevice } from '../services/device.service';
import { pool } from '../db/pool';
import { AuthRequest } from '../types';

const router = Router();

router.use(requireAuth);

// POST /api/devices/register — register a new device, returns deviceId + token
router.post('/register', async (req: AuthRequest, res: Response) => {
  const { name, type } = req.body;
  if (!name || !type) {
    res.status(400).json({ error: 'name and type are required' });
    return;
  }
  try {
    const result = await registerDevice({ userId: req.user!.userId, name, type });
    res.status(201).json({ deviceId: result.deviceId, token: result.token });
  } catch {
    res.status(500).json({ error: 'Device registration failed' });
  }
});

// GET /api/devices — list devices for the authenticated user
router.get('/', async (req: AuthRequest, res: Response) => {
  try {
    const devices = await getUserDevices(req.user!.userId);
    res.json({ devices });
  } catch {
    res.status(500).json({ error: 'Failed to fetch devices' });
  }
});

// PATCH /api/devices/:id — toggle sync_enabled or rename
router.patch('/:id', async (req: AuthRequest, res: Response) => {
  const { syncEnabled, name } = req.body as { syncEnabled?: boolean; name?: string };

  const sets: string[] = [];
  const params: unknown[] = [];

  if (syncEnabled !== undefined) {
    params.push(syncEnabled); sets.push(`sync_enabled = $${params.length}`);
  }
  if (name) {
    params.push(name); sets.push(`name = $${params.length}`);
  }
  if (sets.length === 0) { res.status(400).json({ error: 'No fields to update' }); return; }

  params.push(req.params['id'], req.user!.userId);
  const { rows } = await pool.query(
    `UPDATE devices SET ${sets.join(', ')} WHERE id = $${params.length - 1} AND user_id = $${params.length} RETURNING *`,
    params
  );
  if (!rows[0]) { res.status(404).json({ error: 'Device not found' }); return; }
  res.json({ device: rows[0] });
});

// DELETE /api/devices/:id — remove a device
router.delete('/:id', async (req: AuthRequest, res: Response) => {
  try {
    const deleted = await removeDevice(String(req.params.id), req.user!.userId);
    if (!deleted) {
      res.status(404).json({ error: 'Device not found' });
      return;
    }
    res.json({ message: 'Device removed' });
  } catch {
    res.status(500).json({ error: 'Failed to remove device' });
  }
});

export default router;
