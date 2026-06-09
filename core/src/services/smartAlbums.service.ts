import { pool } from '../db/pool';

interface AlbumCreated { albumId: string; name: string; photoCount: number }

async function upsertAlbum(
  userId: string, name: string, type: string
): Promise<string> {
  const { rows } = await pool.query<{ id: string }>(
    `INSERT INTO albums (user_id, name, type)
     VALUES ($1, $2, $3)
     ON CONFLICT DO NOTHING
     RETURNING id`,
    [userId, name, type]
  );
  if (rows[0]) return rows[0].id;
  // Album already exists — fetch it
  const { rows: existing } = await pool.query<{ id: string }>(
    'SELECT id FROM albums WHERE user_id = $1 AND name = $2',
    [userId, name]
  );
  return existing[0]!.id;
}

async function addPhotosToAlbum(albumId: string, photoIds: string[]): Promise<void> {
  for (const photoId of photoIds) {
    await pool.query(
      `INSERT INTO album_items (album_id, photo_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
      [albumId, photoId]
    );
  }
}

// ── Trips ─────────────────────────────────────────────────────────────────────
// A trip = photos in the same city taken within a 7-day window, min 3 photos.

export async function generateTripAlbums(userId: string): Promise<AlbumCreated[]> {
  const { rows } = await pool.query<{
    city: string; country: string;
    day: string; photo_ids: string;
  }>(
    `SELECT
       pl.city,
       pl.country,
       DATE_TRUNC('day', COALESCE(p.taken_at, f.modified_at))::date::text AS day,
       STRING_AGG(p.id::text, ',') AS photo_ids
     FROM photos p
     JOIN files   f  ON f.id  = p.file_id
     JOIN devices d  ON d.id  = f.device_id
     JOIN photo_locations pl ON pl.photo_id = p.id
     WHERE d.user_id = $1 AND pl.city IS NOT NULL
     GROUP BY pl.city, pl.country, DATE_TRUNC('day', COALESCE(p.taken_at, f.modified_at))
     HAVING COUNT(*) >= 2
     ORDER BY pl.city, day`,
    [userId]
  );

  // Merge consecutive days in the same city into a single trip
  interface Segment { city: string; country: string; days: string[]; photoIds: string[] }
  const segments: Segment[] = [];

  for (const row of rows) {
    const ids = row.photo_ids.split(',');
    const last = segments[segments.length - 1];
    const prevDay = last?.days[last.days.length - 1];
    const sameLoc = last?.city === row.city && last?.country === row.country;
    const consecutive = prevDay && dayDiff(prevDay, row.day) <= 7;

    if (last && sameLoc && consecutive) {
      last.days.push(row.day);
      last.photoIds.push(...ids);
    } else {
      segments.push({ city: row.city, country: row.country, days: [row.day], photoIds: ids });
    }
  }

  const created: AlbumCreated[] = [];
  for (const seg of segments) {
    if (seg.photoIds.length < 3) continue;
    const dateLabel = seg.days[0]!.slice(0, 7); // YYYY-MM
    const name = `${seg.city}, ${seg.country} — ${dateLabel}`;
    const albumId = await upsertAlbum(userId, name, 'trip');
    await addPhotosToAlbum(albumId, seg.photoIds);
    created.push({ albumId, name, photoCount: seg.photoIds.length });
  }
  return created;
}

function dayDiff(a: string, b: string): number {
  return Math.abs(new Date(b).getTime() - new Date(a).getTime()) / 86_400_000;
}

// ── People ────────────────────────────────────────────────────────────────────
// One album per named face cluster.

export async function generatePeopleAlbums(userId: string): Promise<AlbumCreated[]> {
  const { rows } = await pool.query<{
    cluster_id: string; person_name: string; photo_ids: string;
  }>(
    `SELECT
       fc.id AS cluster_id,
       fc.person_name,
       STRING_AGG(DISTINCT p.id::text, ',') AS photo_ids
     FROM face_clusters fc
     JOIN faces   fa ON fa.cluster_id = fc.id
     JOIN photos  p  ON p.id = fa.photo_id
     JOIN files   f  ON f.id = p.file_id
     JOIN devices d  ON d.id = f.device_id
     WHERE d.user_id = $1 AND fc.person_name IS NOT NULL
     GROUP BY fc.id, fc.person_name
     HAVING COUNT(DISTINCT p.id) >= 2`,
    [userId]
  );

  const created: AlbumCreated[] = [];
  for (const row of rows) {
    const name = `Photos of ${row.person_name}`;
    const ids  = row.photo_ids.split(',');
    const albumId = await upsertAlbum(userId, name, 'people');
    await addPhotosToAlbum(albumId, ids);
    created.push({ albumId, name, photoCount: ids.length });
  }
  return created;
}

// ── Pets ──────────────────────────────────────────────────────────────────────
// One album per animal tag with enough photos.

const PET_TAGS = ['dog', 'cat', 'rabbit', 'hamster', 'horse', 'bird', 'parrot', 'goldfish', 'koala', 'lion', 'tiger', 'elephant'];

export async function generatePetAlbums(userId: string): Promise<AlbumCreated[]> {
  const { rows } = await pool.query<{ tag: string; photo_ids: string }>(
    `SELECT
       pt.tag,
       STRING_AGG(DISTINCT p.id::text, ',') AS photo_ids
     FROM photo_tags pt
     JOIN photos  p ON p.id = pt.photo_id
     JOIN files   f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1 AND pt.tag = ANY($2::text[])
     GROUP BY pt.tag
     HAVING COUNT(DISTINCT p.id) >= 2`,
    [userId, PET_TAGS]
  );

  const created: AlbumCreated[] = [];
  for (const row of rows) {
    const name = `${capitalise(row.tag)}s`;
    const ids  = row.photo_ids.split(',');
    const albumId = await upsertAlbum(userId, name, 'pets');
    await addPhotosToAlbum(albumId, ids);
    created.push({ albumId, name, photoCount: ids.length });
  }
  return created;
}

// ── Places ────────────────────────────────────────────────────────────────────
// One album per city/country with >= 5 photos.

export async function generatePlaceAlbums(userId: string): Promise<AlbumCreated[]> {
  const { rows } = await pool.query<{
    city: string; country: string; photo_ids: string;
  }>(
    `SELECT
       pl.city,
       pl.country,
       STRING_AGG(DISTINCT p.id::text, ',') AS photo_ids
     FROM photo_locations pl
     JOIN photos  p ON p.id = pl.photo_id
     JOIN files   f ON f.id = p.file_id
     JOIN devices d ON d.id = f.device_id
     WHERE d.user_id = $1 AND pl.city IS NOT NULL
     GROUP BY pl.city, pl.country
     HAVING COUNT(DISTINCT p.id) >= 5`,
    [userId]
  );

  const created: AlbumCreated[] = [];
  for (const row of rows) {
    const name = [row.city, row.country].filter(Boolean).join(', ');
    const ids  = row.photo_ids.split(',');
    const albumId = await upsertAlbum(userId, name, 'place');
    await addPhotosToAlbum(albumId, ids);
    created.push({ albumId, name, photoCount: ids.length });
  }
  return created;
}

// ── Generate all ──────────────────────────────────────────────────────────────

export async function generateAllSmartAlbums(userId: string) {
  const [trips, people, pets, places] = await Promise.all([
    generateTripAlbums(userId),
    generatePeopleAlbums(userId),
    generatePetAlbums(userId),
    generatePlaceAlbums(userId),
  ]);
  return { trips, people, pets, places };
}

function capitalise(s: string) { return s.charAt(0).toUpperCase() + s.slice(1); }
