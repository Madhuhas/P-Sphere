import { Router, Response } from 'express';
import { requireAuth } from '../middleware/auth';
import { chat, isAvailable, ChatMessage } from '../services/llm.service';
import { searchPhotos } from '../services/photoSearch.service';
import { autoCreateAlbums, generateLibrarySummary, getCleanupSuggestions } from '../services/automation.service';
import { cacheGet, cacheSet } from '../utils/cache';
import { AuthRequest } from '../types';

const SUMMARY_TTL = 300; // 5 minutes

const router = Router();

router.use(requireAuth);

// GET /api/ai/status
router.get('/status', async (_req, res: Response) => {
  const available = await isAvailable();
  res.json({ available, provider: process.env.LLM_PROVIDER ?? 'ollama' });
});

// POST /api/ai/chat
// Body: { messages: ChatMessage[], systemPrompt?: string }
router.post('/chat', async (req: AuthRequest, res: Response) => {
  const { messages, systemPrompt } = req.body as {
    messages?: ChatMessage[];
    systemPrompt?: string;
  };

  if (!Array.isArray(messages) || messages.length === 0) {
    res.status(400).json({ error: 'messages[] is required' });
    return;
  }

  const fullMessages: ChatMessage[] = [
    {
      role: 'system',
      content: systemPrompt ??
        'You are a personal assistant for a private photo library called P-Sphere. ' +
        'Help the user find, organise, and understand their photos.',
    },
    ...messages,
  ];

  try {
    const response = await chat(fullMessages);
    res.json({ reply: response.message, model: response.model });
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : 'LLM request failed';
    res.status(502).json({ error: msg });
  }
});

// POST /api/ai/search
// Body: { query: string, limit?: number }
router.post('/search', async (req: AuthRequest, res: Response) => {
  const { query, limit } = req.body as { query?: string; limit?: number };

  if (!query || typeof query !== 'string' || query.trim().length === 0) {
    res.status(400).json({ error: 'query is required' });
    return;
  }

  try {
    const result = await searchPhotos(req.user!.userId, query.trim(), limit ?? 20);
    res.json(result);
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : 'Search failed';
    res.status(500).json({ error: msg });
  }
});

// POST /api/ai/album/create — LLM groups photos into named smart albums
router.post('/album/create', async (req: AuthRequest, res: Response) => {
  try {
    const result = await autoCreateAlbums(req.user!.userId);
    res.status(201).json(result);
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Auto-album failed' });
  }
});

// GET /api/ai/summary — LLM-written overview of the whole library
router.get('/summary', async (req: AuthRequest, res: Response) => {
  const cacheKey = `summary:${req.user!.userId}`;
  const cached = await cacheGet<unknown>(cacheKey);
  if (cached) { res.json(cached); return; }

  try {
    const result = await generateLibrarySummary(req.user!.userId);
    await cacheSet(cacheKey, result, SUMMARY_TTL);
    res.json(result);
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Summary failed' });
  }
});

// GET /api/ai/cleanup — detect undated, tiny, and duplicate photos
router.get('/cleanup', async (req: AuthRequest, res: Response) => {
  try {
    const result = await getCleanupSuggestions(req.user!.userId);
    res.json(result);
  } catch (err: unknown) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Cleanup analysis failed' });
  }
});

export default router;
