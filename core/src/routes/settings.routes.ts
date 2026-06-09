import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { pool } from '../db/pool';
import { config } from '../config/env';
import { AuthRequest } from '../types';

const router = Router();
router.use(requireAuth);

const DEFAULTS = {
  ai: {
    provider: config.llm.provider,
    model:    config.llm.model,
    baseUrl:  config.llm.baseUrl,
  },
  storage: {
    maxUploadMB:  500,
    dataRootPath: process.env.DATA_ROOT ?? './data',
  },
  notifications: {
    syncComplete:  true,
    newDuplicates: true,
    weeklyDigest:  false,
  },
  vision: {
    faceDetectionConfidence: 0.45,
    objectTagConfidence:     0.05,
    modelsDir: process.env.MODELS_DIR ?? './models',
  },
};

// GET /api/settings
router.get('/', async (req: AuthRequest, res: Response) => {
  const { rows } = await pool.query(
    'SELECT settings FROM user_settings WHERE user_id = $1',
    [req.user!.userId]
  );

  const saved = (rows[0]?.settings ?? {}) as Record<string, unknown>;

  // Deep merge defaults with user overrides
  const merged = {
    ai:            { ...DEFAULTS.ai,            ...(saved['ai']            as object ?? {}) },
    storage:       { ...DEFAULTS.storage,       ...(saved['storage']       as object ?? {}) },
    notifications: { ...DEFAULTS.notifications, ...(saved['notifications'] as object ?? {}) },
    vision:        { ...DEFAULTS.vision,        ...(saved['vision']        as object ?? {}) },
  };

  res.json({ settings: merged, defaults: DEFAULTS });
});

// PATCH /api/settings — partial update (deep merge into existing)
router.patch('/', async (req: AuthRequest, res: Response) => {
  const patch = req.body as Record<string, unknown>;
  if (!patch || typeof patch !== 'object') {
    res.status(400).json({ error: 'Body must be a JSON object' });
    return;
  }

  // Fetch existing
  const { rows } = await pool.query(
    'SELECT settings FROM user_settings WHERE user_id = $1',
    [req.user!.userId]
  );
  const existing = (rows[0]?.settings ?? {}) as Record<string, unknown>;

  // Merge top-level sections
  const updated: Record<string, unknown> = { ...existing };
  for (const key of Object.keys(patch)) {
    if (typeof patch[key] === 'object' && patch[key] !== null && !Array.isArray(patch[key])) {
      updated[key] = { ...(existing[key] as object ?? {}), ...(patch[key] as object) };
    } else {
      updated[key] = patch[key];
    }
  }

  await pool.query(
    `INSERT INTO user_settings (user_id, settings) VALUES ($1, $2)
     ON CONFLICT (user_id) DO UPDATE SET settings = $2`,
    [req.user!.userId, JSON.stringify(updated)]
  );

  res.json({ settings: updated });
});

// POST /api/settings/reset — restore defaults
router.post('/reset', async (req: AuthRequest, res: Response) => {
  await pool.query(
    `INSERT INTO user_settings (user_id, settings) VALUES ($1, '{}')
     ON CONFLICT (user_id) DO UPDATE SET settings = '{}'`,
    [req.user!.userId]
  );
  res.json({ settings: DEFAULTS });
});

export default router;
