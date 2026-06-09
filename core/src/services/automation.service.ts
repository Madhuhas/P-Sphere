import { pool } from '../db/pool';
import { chat } from './llm.service';
import { parseLlmJson } from '../utils/parseLlmJson';

// ── Shared helpers ─────────────────────────────────────────────────────────────

async function fetchPhotoMeta(userId: string, limit = 300) {
  const { rows } = await pool.query<{
    id: string;
    taken_at: string | null;
    city: string | null;
    country: string | null;
    tags: string | null;
    people: string | null;
    width: number | null;
    height: number | null;
    hash: string | null;
  }>(
    `SELECT
       p.id,
       p.taken_at,
       p.width,
       p.height,
       pl.city,
       pl.country,
       STRING_AGG(DISTINCT pt.tag, ', ')         AS tags,
       STRING_AGG(DISTINCT fc.person_name, ', ')
         FILTER (WHERE fc.person_name IS NOT NULL) AS people,
       f.hash
     FROM photos p
     JOIN files   f  ON f.id  = p.file_id
     JOIN devices d  ON d.id  = f.device_id
     LEFT JOIN photo_locations pl ON pl.photo_id = p.id
     LEFT JOIN photo_tags      pt ON pt.photo_id = p.id
     LEFT JOIN faces           fa ON fa.photo_id = p.id
     LEFT JOIN face_clusters   fc ON fc.id = fa.cluster_id
     WHERE d.user_id = $1
     GROUP BY p.id, p.taken_at, p.width, p.height, pl.city, pl.country, f.hash
     ORDER BY p.taken_at ASC NULLS LAST
     LIMIT $2`,
    [userId, limit]
  );
  return rows;
}

function buildPhotoLine(r: { id: string; taken_at: string | null; city: string | null; country: string | null; tags: string | null; people: string | null }, i: number) {
  return (
    `[${i}] date=${r.taken_at?.slice(0, 10) ?? '?'} ` +
    `place=${[r.city, r.country].filter(Boolean).join(', ') || '?'} ` +
    `people=${r.people ?? 'none'} tags=${r.tags ?? 'none'}`
  );
}

// ── Auto-album creation ────────────────────────────────────────────────────────

export interface AutoAlbumResult {
  created: { albumId: string; name: string; photoCount: number }[];
  explanation: string;
}

export async function autoCreateAlbums(userId: string): Promise<AutoAlbumResult> {
  const rows = await fetchPhotoMeta(userId, 200);
  if (rows.length === 0) return { created: [], explanation: 'No photos to group.' };

  const summary = rows.map(buildPhotoLine).join('\n');

  const systemPrompt =
    'You are a photo organiser. Group the photos below into meaningful albums ' +
    '(trips, events, people, occasions). Return ONLY valid JSON: ' +
    '{"albums":[{"name":"Goa Trip Jan 2024","indices":[0,1,4]},...],"explanation":"..."}. ' +
    'Create 2–8 albums. Each photo may appear in at most one album.';

  let created: AutoAlbumResult['created'] = [];
  let explanation = '';

  try {
    const response = await chat([
      { role: 'system', content: systemPrompt },
      { role: 'user',   content: `Photos:\n${summary}` },
    ]);

    const parsed = parseLlmJson<{ albums?: { name: string; indices: number[] }[]; explanation?: string }>(response.message);
    if (!parsed) throw new Error('No JSON in LLM response');

    explanation = parsed.explanation ?? '';

    for (const album of parsed.albums ?? []) {
      const validIndices = (album.indices ?? []).filter((i) => i >= 0 && i < rows.length);
      if (validIndices.length === 0) continue;

      const { rows: albumRows } = await pool.query<{ id: string }>(
        `INSERT INTO albums (user_id, name, type) VALUES ($1, $2, 'smart') RETURNING id`,
        [userId, album.name]
      );
      const albumId = albumRows[0]!.id;

      for (const idx of validIndices) {
        await pool.query(
          `INSERT INTO album_items (album_id, photo_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
          [albumId, rows[idx]!.id]
        );
      }

      created.push({ albumId, name: album.name, photoCount: validIndices.length });
    }
  } catch (err) {
    explanation = `Auto-album failed: ${err instanceof Error ? err.message : String(err)}`;
  }

  return { created, explanation };
}

// ── Library summary ────────────────────────────────────────────────────────────

export interface SummaryResult {
  summary: string;
  photoCount: number;
  dateRange: { earliest: string | null; latest: string | null };
  topLocations: string[];
  topTags: string[];
}

export async function generateLibrarySummary(userId: string): Promise<SummaryResult> {
  const rows = await fetchPhotoMeta(userId, 300);

  const datesSorted = rows.map((r) => r.taken_at).filter(Boolean).sort() as string[];
  const earliest = datesSorted[0] ?? null;
  const latest   = datesSorted[datesSorted.length - 1] ?? null;

  // Count locations and tags
  const locationCount: Record<string, number> = {};
  const tagCount: Record<string, number> = {};

  for (const r of rows) {
    const loc = [r.city, r.country].filter(Boolean).join(', ');
    if (loc) locationCount[loc] = (locationCount[loc] ?? 0) + 1;
    for (const tag of r.tags?.split(', ') ?? []) {
      if (tag) tagCount[tag] = (tagCount[tag] ?? 0) + 1;
    }
  }

  const topLocations = Object.entries(locationCount)
    .sort((a, b) => b[1] - a[1]).slice(0, 5).map(([k]) => k);
  const topTags = Object.entries(tagCount)
    .sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k]) => k);

  const context =
    `Library has ${rows.length} photos from ${earliest?.slice(0, 10) ?? '?'} to ${latest?.slice(0, 10) ?? '?'}. ` +
    `Top locations: ${topLocations.join(', ') || 'unknown'}. ` +
    `Common tags: ${topTags.join(', ') || 'none'}.`;

  let summary = context;

  try {
    const response = await chat([
      { role: 'system', content: 'You write friendly, concise summaries of personal photo libraries in 2-3 sentences.' },
      { role: 'user',   content: context },
    ]);
    summary = response.message;
  } catch {
    // Return the raw context as fallback
  }

  return { summary, photoCount: rows.length, dateRange: { earliest, latest }, topLocations, topTags };
}

// ── Cleanup suggestions ────────────────────────────────────────────────────────

export interface CleanupSuggestion {
  photoId: string;
  reason: string;
  thumb_url: string;
}

export interface CleanupResult {
  suggestions: CleanupSuggestion[];
  summary: string;
}

export async function getCleanupSuggestions(userId: string): Promise<CleanupResult> {
  const suggestions: CleanupSuggestion[] = [];

  // 1 — Photos with no EXIF date (likely screenshots or WhatsApp forwards)
  const { rows: noDate } = await pool.query<{ id: string }>(
    `SELECT p.id FROM photos p
     JOIN files f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1 AND p.taken_at IS NULL
     LIMIT 50`,
    [userId]
  );
  for (const r of noDate) {
    suggestions.push({ photoId: r.id, reason: 'No capture date — may be a screenshot or forwarded image', thumb_url: `/api/photos/thumb/${r.id}/sm` });
  }

  // 2 — Very small photos (likely thumbnails or icons, width or height < 300px)
  const { rows: small } = await pool.query<{ id: string; width: number; height: number }>(
    `SELECT p.id, p.width, p.height FROM photos p
     JOIN files f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1
       AND p.width IS NOT NULL AND p.height IS NOT NULL
       AND (p.width < 300 OR p.height < 300)
     LIMIT 50`,
    [userId]
  );
  for (const r of small) {
    suggestions.push({ photoId: r.id, reason: `Very small image (${r.width}×${r.height}px) — likely a thumbnail`, thumb_url: `/api/photos/thumb/${r.id}/sm` });
  }

  // 3 — Duplicate hashes (same file uploaded from multiple devices)
  const { rows: dupes } = await pool.query<{ id: string; hash: string; device_count: number }>(
    `SELECT p.id, f.hash, COUNT(DISTINCT d.id) AS device_count
     FROM photos p
     JOIN files   f ON f.id  = p.file_id
     JOIN devices d ON d.id  = f.device_id
     WHERE d.user_id = $1
     GROUP BY p.id, f.hash
     HAVING COUNT(DISTINCT d.id) > 1
     LIMIT 30`,
    [userId]
  );
  for (const r of dupes) {
    suggestions.push({ photoId: r.id, reason: `Exact duplicate across ${r.device_count} devices — safe to consolidate`, thumb_url: `/api/photos/thumb/${r.id}/sm` });
  }

  const total = suggestions.length;
  let summary = `Found ${total} cleanup suggestion${total !== 1 ? 's' : ''}.`;

  if (total > 0) {
    try {
      const breakdown = `${noDate.length} undated, ${small.length} tiny, ${dupes.length} duplicates.`;
      const response = await chat([
        { role: 'system', content: 'You give short, friendly advice about photo library cleanup in 1-2 sentences.' },
        { role: 'user',   content: `Library cleanup analysis: ${breakdown}` },
      ]);
      summary = response.message;
    } catch { /* keep default summary */ }
  }

  return { suggestions: suggestions.slice(0, 100), summary };
}
