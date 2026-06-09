import { pool } from './pool';

const schema = `
  CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       TEXT UNIQUE NOT NULL,
    password    TEXT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
  );

  CREATE TABLE IF NOT EXISTS devices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID REFERENCES users(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL,
    device_token TEXT UNIQUE NOT NULL,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
  );

  CREATE TABLE IF NOT EXISTS file_conflicts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID REFERENCES users(id) ON DELETE CASCADE,
    path         TEXT NOT NULL,
    file_id_a    UUID REFERENCES files(id) ON DELETE CASCADE,
    file_id_b    UUID REFERENCES files(id) ON DELETE CASCADE,
    resolved_at  TIMESTAMPTZ,
    kept_file_id UUID REFERENCES files(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (file_id_a, file_id_b)
  );

  CREATE TABLE IF NOT EXISTS files (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id   UUID REFERENCES devices(id) ON DELETE CASCADE,
    path        TEXT NOT NULL,
    hash        TEXT NOT NULL,
    size        BIGINT NOT NULL DEFAULT 0,
    mime_type   TEXT,
    is_photo    BOOLEAN NOT NULL DEFAULT FALSE,
    modified_at TIMESTAMPTZ NOT NULL,
    synced_at   TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (device_id, path)
  );

  CREATE TABLE IF NOT EXISTS photos (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id    UUID UNIQUE REFERENCES files(id) ON DELETE CASCADE,
    taken_at   TIMESTAMPTZ,
    width      INT,
    height     INT,
    camera     TEXT,
    exif_json  JSONB,
    thumb_sm   TEXT,
    thumb_lg   TEXT,
    phash      CHAR(16),
    created_at TIMESTAMPTZ DEFAULT NOW()
  );

  CREATE TABLE IF NOT EXISTS photo_locations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    photo_id   UUID UNIQUE REFERENCES photos(id) ON DELETE CASCADE,
    lat        DOUBLE PRECISION,
    lon        DOUBLE PRECISION,
    city       TEXT,
    country    TEXT,
    source     TEXT NOT NULL DEFAULT 'gps'
  );

  CREATE TABLE IF NOT EXISTS user_settings (
    user_id  UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    settings JSONB NOT NULL DEFAULT '{}'
  );

  CREATE TABLE IF NOT EXISTS albums (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    type       TEXT NOT NULL DEFAULT 'manual',
    summary    TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
  );

  CREATE TABLE IF NOT EXISTS album_items (
    album_id   UUID REFERENCES albums(id) ON DELETE CASCADE,
    photo_id   UUID REFERENCES photos(id) ON DELETE CASCADE,
    added_at   TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (album_id, photo_id)
  );

  CREATE TABLE IF NOT EXISTS photo_tags (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    photo_id UUID REFERENCES photos(id) ON DELETE CASCADE,
    tag      TEXT NOT NULL,
    type     TEXT NOT NULL DEFAULT 'object',
    score    REAL NOT NULL DEFAULT 0,
    UNIQUE (photo_id, tag)
  );

  CREATE TABLE IF NOT EXISTS face_clusters (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    label       TEXT,
    person_name TEXT,
    confidence  REAL DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS faces (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    photo_id         UUID REFERENCES photos(id) ON DELETE CASCADE,
    bbox             JSONB NOT NULL,
    confidence       REAL NOT NULL DEFAULT 0,
    embedding_vector REAL[],
    cluster_id       UUID REFERENCES face_clusters(id) ON DELETE SET NULL
  );
`;

const indexes = `
  CREATE INDEX IF NOT EXISTS idx_photos_taken_at      ON photos(taken_at DESC NULLS LAST);
  CREATE INDEX IF NOT EXISTS idx_photos_phash          ON photos(phash) WHERE phash IS NOT NULL;
  CREATE INDEX IF NOT EXISTS idx_photos_file_id        ON photos(file_id);
  CREATE INDEX IF NOT EXISTS idx_files_device_id       ON files(device_id);
  CREATE INDEX IF NOT EXISTS idx_files_device_photo    ON files(device_id) WHERE is_photo = TRUE;
  CREATE INDEX IF NOT EXISTS idx_files_hash            ON files(hash);
  CREATE INDEX IF NOT EXISTS idx_photo_locations_photo ON photo_locations(photo_id);
  CREATE INDEX IF NOT EXISTS idx_photo_tags_photo      ON photo_tags(photo_id);
  CREATE INDEX IF NOT EXISTS idx_photo_tags_type       ON photo_tags(type, score DESC);
  CREATE INDEX IF NOT EXISTS idx_faces_photo_id        ON faces(photo_id);
  CREATE INDEX IF NOT EXISTS idx_faces_cluster_id      ON faces(cluster_id) WHERE cluster_id IS NOT NULL;
  CREATE INDEX IF NOT EXISTS idx_album_items_album     ON album_items(album_id);
  CREATE INDEX IF NOT EXISTS idx_devices_user_id       ON devices(user_id);
`;

export async function runMigrations(): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query(schema);
    await client.query(indexes);
    console.log('Migrations and indexes applied.');
  } finally {
    client.release();
  }
}
