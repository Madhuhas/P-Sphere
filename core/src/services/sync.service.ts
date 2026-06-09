import { pool } from '../db/pool';

export interface FileRecord {
  path: string;
  hash: string;
  size: number;
  mimeType: string | null;
  isPhoto: boolean;
  modifiedAt: string;
}

/**
 * Given a list of hashes the device already has, return only the hashes
 * that do NOT exist in the DB for this device — these need to be uploaded.
 */
export async function checkHashes(deviceId: string, hashes: string[]): Promise<string[]> {
  if (hashes.length === 0) return [];

  const { rows } = await pool.query<{ hash: string }>(
    `SELECT hash FROM files WHERE device_id = $1 AND hash = ANY($2::text[])`,
    [deviceId, hashes]
  );

  const known = new Set(rows.map((r) => r.hash));
  return hashes.filter((h) => !known.has(h));
}

/**
 * Upsert a file record after a successful upload.
 */
export async function upsertFile(deviceId: string, file: FileRecord): Promise<void> {
  await pool.query(
    `INSERT INTO files (device_id, path, hash, size, mime_type, is_photo, modified_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     ON CONFLICT (device_id, path)
     DO UPDATE SET
       hash        = EXCLUDED.hash,
       size        = EXCLUDED.size,
       mime_type   = EXCLUDED.mime_type,
       is_photo    = EXCLUDED.is_photo,
       modified_at = EXCLUDED.modified_at,
       synced_at   = NOW()`,
    [deviceId, file.path, file.hash, file.size, file.mimeType, file.isPhoto, file.modifiedAt]
  );
}
