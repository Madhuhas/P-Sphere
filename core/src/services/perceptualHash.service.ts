import sharp from 'sharp';
import { pool } from '../db/pool';

// ── dHash (difference hash) ───────────────────────────────────────────────────
// Resize to 9×8 grayscale, compare adjacent horizontal pixels → 64-bit hash.
// Similar images produce hashes with low Hamming distance.

const DHASH_W = 9;
const DHASH_H = 8;

export async function computeDHash(imagePath: string): Promise<string> {
  const { data } = await sharp(imagePath)
    .rotate()
    .resize(DHASH_W, DHASH_H, { fit: 'fill' })
    .grayscale()
    .raw()
    .toBuffer({ resolveWithObject: true });

  let bits = BigInt(0);
  for (let row = 0; row < DHASH_H; row++) {
    for (let col = 0; col < DHASH_W - 1; col++) {
      const idx = row * DHASH_W + col;
      if (data[idx]! > data[idx + 1]!) bits = (bits << BigInt(1)) | BigInt(1);
      else                              bits =  bits << BigInt(1);
    }
  }
  // 64 bits → 16 hex chars, zero-padded
  return bits.toString(16).padStart(16, '0');
}

export async function storeDHash(photoId: string, hash: string): Promise<void> {
  await pool.query('UPDATE photos SET phash = $1 WHERE id = $2', [hash, photoId]);
}

// ── Hamming distance ──────────────────────────────────────────────────────────

function hammingDistance(a: string, b: string): number {
  const x = BigInt(`0x${a}`) ^ BigInt(`0x${b}`);
  let dist = 0;
  let n = x;
  while (n > BigInt(0)) {
    dist += Number(n & BigInt(1));
    n >>= BigInt(1);
  }
  return dist;
}

export function similarityScore(hashA: string, hashB: string): number {
  return 1 - hammingDistance(hashA, hashB) / 64;
}

// ── Duplicate detection ───────────────────────────────────────────────────────

export interface DuplicateGroup {
  photos: { id: string; taken_at: string | null; thumb_url: string; phash: string }[];
  similarity: number;
}

const DUPLICATE_THRESHOLD = 0.85;   // ≥ 85 % similar → near-duplicate

export async function findDuplicates(userId: string): Promise<DuplicateGroup[]> {
  const { rows } = await pool.query<{
    id: string; taken_at: string | null; phash: string;
  }>(
    `SELECT p.id, p.taken_at, p.phash
     FROM photos p
     JOIN files   f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1 AND p.phash IS NOT NULL
     ORDER BY p.taken_at ASC NULLS LAST`,
    [userId]
  );

  // O(n²) pairwise comparison — acceptable for personal libraries (< 100k photos)
  // For larger libraries, use VP-tree or BK-tree indexing.
  const groups: DuplicateGroup[] = [];
  const used = new Set<string>();

  for (let i = 0; i < rows.length; i++) {
    if (used.has(rows[i]!.id)) continue;
    const group: DuplicateGroup['photos'] = [{
      id: rows[i]!.id,
      taken_at: rows[i]!.taken_at,
      phash: rows[i]!.phash,
      thumb_url: `/api/photos/thumb/${rows[i]!.id}/sm`,
    }];
    let minSim = 1;

    for (let j = i + 1; j < rows.length; j++) {
      if (used.has(rows[j]!.id)) continue;
      const sim = similarityScore(rows[i]!.phash, rows[j]!.phash);
      if (sim >= DUPLICATE_THRESHOLD) {
        group.push({
          id: rows[j]!.id,
          taken_at: rows[j]!.taken_at,
          phash: rows[j]!.phash,
          thumb_url: `/api/photos/thumb/${rows[j]!.id}/sm`,
        });
        used.add(rows[j]!.id);
        if (sim < minSim) minSim = sim;
      }
    }

    if (group.length > 1) {
      used.add(rows[i]!.id);
      groups.push({ photos: group, similarity: Math.round(minSim * 100) / 100 });
    }
  }

  return groups.sort((a, b) => b.similarity - a.similarity);
}

