import { pool } from '../db/pool';
import { chat } from './llm.service';
import { parseLlmJson } from '../utils/parseLlmJson';

// ── Types ─────────────────────────────────────────────────────────────────────

interface RawCandidate {
  id: string;
  taken_at: string | null;
  camera: string | null;
  city: string | null;
  country: string | null;
  tags: string | null;
  tag_score: number | null;
  people: string | null;
}

export interface SearchResult {
  id: string;
  taken_at: string | null;
  city: string | null;
  country: string | null;
  tags: string[];
  people: string[];
  thumb_url: string;
  score: number;           // 0-1 relevance from LLM (or keyword fallback)
}

export interface SearchResponse {
  photos: SearchResult[];
  total: number;
  explanation: string;
  fallback: boolean;
}

// ── Date extraction ───────────────────────────────────────────────────────────

interface DateRange { from?: string; to?: string }

function extractDateRange(query: string): DateRange {
  const q = query.toLowerCase();
  const now = new Date();
  const y = now.getFullYear();

  // "last year" / "this year"
  if (/last year/.test(q)) return { from: `${y - 1}-01-01`, to: `${y - 1}-12-31` };
  if (/this year/.test(q)) return { from: `${y}-01-01` };

  // 4-digit year
  const yearMatch = q.match(/\b(20\d{2})\b/);
  if (yearMatch) return { from: `${yearMatch[1]}-01-01`, to: `${yearMatch[1]}-12-31` };

  // Month name + optional year
  const months: Record<string, string> = {
    january:'01', february:'02', march:'03', april:'04',
    may:'05', june:'06', july:'07', august:'08',
    september:'09', october:'10', november:'11', december:'12',
  };
  for (const [name, num] of Object.entries(months)) {
    const re = new RegExp(`${name}\\s*(20\\d{2})?`);
    const m = q.match(re);
    if (m) {
      const yr = m[1] ?? String(y);
      const lastDay = new Date(parseInt(yr), parseInt(num), 0).getDate();
      return { from: `${yr}-${num}-01`, to: `${yr}-${num}-${lastDay}` };
    }
  }

  return {};
}

// ── Tag-weighted pre-filter ───────────────────────────────────────────────────

function keywordScore(candidate: RawCandidate, keywords: string[]): number {
  const text = [
    candidate.tags ?? '',
    candidate.city ?? '',
    candidate.country ?? '',
    candidate.people ?? '',
    candidate.camera ?? '',
  ].join(' ').toLowerCase();

  const hits = keywords.filter((kw) => text.includes(kw)).length;
  const tagBoost = candidate.tag_score ?? 0;
  return hits + tagBoost * 0.5;
}

// ── Main search ───────────────────────────────────────────────────────────────

export async function searchPhotos(
  userId: string,
  query: string,
  limit = 20
): Promise<SearchResponse> {
  const dateRange = extractDateRange(query);
  const keywords  = query.toLowerCase().split(/\W+/).filter((w) => w.length > 2);

  // Build date filter clause
  const dateParams: unknown[] = [userId];
  const dateClauses: string[] = [];
  if (dateRange.from) { dateParams.push(dateRange.from); dateClauses.push(`p.taken_at >= $${dateParams.length}`); }
  if (dateRange.to)   { dateParams.push(dateRange.to);   dateClauses.push(`p.taken_at <= $${dateParams.length}`); }
  const dateWhere = dateClauses.length ? `AND ${dateClauses.join(' AND ')}` : '';

  // Rich candidate query: tags + locations + face cluster person names
  const { rows } = await pool.query<RawCandidate>(
    `SELECT
       p.id,
       p.taken_at,
       p.camera,
       pl.city,
       pl.country,
       STRING_AGG(DISTINCT pt.tag,        ', ' ORDER BY pt.tag)        AS tags,
       MAX(pt.score)                                                    AS tag_score,
       STRING_AGG(DISTINCT fc.person_name, ', ' ORDER BY fc.person_name)
         FILTER (WHERE fc.person_name IS NOT NULL)                      AS people
     FROM photos p
     JOIN files   f  ON f.id  = p.file_id
     JOIN devices d  ON d.id  = f.device_id
     LEFT JOIN photo_locations pl ON pl.photo_id = p.id
     LEFT JOIN photo_tags      pt ON pt.photo_id = p.id
     LEFT JOIN faces           fa ON fa.photo_id = p.id
     LEFT JOIN face_clusters   fc ON fc.id = fa.cluster_id
     WHERE d.user_id = $1 ${dateWhere}
     GROUP BY p.id, p.taken_at, p.camera, pl.city, pl.country
     ORDER BY p.taken_at DESC NULLS LAST
     LIMIT 300`,
    dateParams
  );

  if (rows.length === 0) {
    return { photos: [], total: 0, explanation: 'No photos found in your library.', fallback: false };
  }

  // Pre-filter: keep top 80 candidates by keyword relevance to reduce LLM prompt size
  const scored = rows
    .map((r) => ({ row: r, ks: keywordScore(r, keywords) }))
    .sort((a, b) => b.ks - a.ks);

  const candidates = (keywords.length > 0
    ? scored.filter((s) => s.ks > 0).slice(0, 80).map((s) => s.row)
    : rows.slice(0, 80)
  );

  // If zero keyword matches, fall back to all 80 most-recent candidates
  const pool80 = candidates.length > 0 ? candidates : rows.slice(0, 80);

  // ── LLM ranking ─────────────────────────────────────────────────────────────
  const systemPrompt =
    'You are a photo search assistant for a private library. ' +
    'Given a list of photos and a search query, return ONLY valid JSON in this exact shape: ' +
    '{"results":[{"index":0,"score":0.95},{"index":3,"score":0.80}],"explanation":"one sentence"}. ' +
    'score is 0-1 relevance. Include only photos that match. Sort by score descending.';

  const candidateSummary = pool80.map((r, i) =>
    `[${i}] date=${r.taken_at?.slice(0, 10) ?? '?'} ` +
    `place=${[r.city, r.country].filter(Boolean).join(', ') || '?'} ` +
    `people=${r.people ?? 'none'} ` +
    `tags=${r.tags ?? 'none'}`
  ).join('\n');

  let results: { index: number; score: number }[] = [];
  let explanation = '';
  let fallback = false;

  try {
    const response = await chat([
      { role: 'system', content: systemPrompt },
      { role: 'user', content: `Query: "${query}"\n\nPhotos:\n${candidateSummary}` },
    ]);

    const parsed = parseLlmJson<{ results?: { index: number; score: number }[]; explanation?: string }>(response.message);
    if (parsed) {
      results     = (parsed.results ?? []).filter((r) => r.index >= 0 && r.index < pool80.length);
      explanation = parsed.explanation ?? '';
    }
  } catch {
    // LLM unavailable — keyword scoring fallback
    fallback = true;
    results = pool80
      .map((r, i) => ({ index: i, score: Math.min(1, keywordScore(r, keywords) / Math.max(keywords.length, 1)) }))
      .filter((r) => r.score > 0)
      .sort((a, b) => b.score - a.score);
    explanation = 'LLM unavailable — keyword relevance ranking used.';
  }

  const photos: SearchResult[] = results.slice(0, limit).map(({ index, score }) => {
    const r = pool80[index]!;
    return {
      id:       r.id,
      taken_at: r.taken_at,
      city:     r.city,
      country:  r.country,
      tags:     r.tags?.split(', ').filter(Boolean) ?? [],
      people:   r.people?.split(', ').filter(Boolean) ?? [],
      thumb_url: `/api/photos/thumb/${r.id}/sm`,
      score:    Math.round(score * 100) / 100,
    };
  });

  return { photos, total: photos.length, explanation, fallback };
}
