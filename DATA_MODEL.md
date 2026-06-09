# P‑Sphere Data Model

This document defines the relational schema used by the Core backend.

---

## 1. Users

```
users
  id UUID PK
  email TEXT UNIQUE
  password_hash TEXT
  created_at TIMESTAMP
```

---

## 2. Devices

```
devices
  id UUID PK
  user_id UUID FK -> users.id
  name TEXT
  type TEXT (android, ios, desktop)
  last_seen TIMESTAMP
  created_at TIMESTAMP
```

---

## 3. Files

```
files
  id UUID PK
  device_id UUID FK -> devices.id
  path TEXT
  hash TEXT
  size BIGINT
  modified_at TIMESTAMP
  mime_type TEXT
  is_photo BOOLEAN
```

---

## 4. Photos

```
photos
  id UUID PK
  file_id UUID FK -> files.id
  taken_at TIMESTAMP
  width INT
  height INT
  exif_json JSONB
```

---

## 5. Locations

```
photo_locations
  id UUID PK
  photo_id UUID FK -> photos.id
  lat DOUBLE
  lon DOUBLE
  city TEXT
  country TEXT
  source TEXT (gps, ai)
```

---

## 6. Faces

```
faces
  id UUID PK
  photo_id UUID FK -> photos.id
  bbox JSONB
  embedding_vector VECTOR(512)
  cluster_id UUID FK -> face_clusters.id
```

---

## 7. Face Clusters

```
face_clusters
  id UUID PK
  user_id UUID FK -> users.id
  label TEXT
  person_name TEXT
  confidence FLOAT
```

---

## 8. Tags

```
photo_tags
  id UUID PK
  photo_id UUID FK -> photos.id
  tag TEXT
  type TEXT (object, scene, face, place)
  score FLOAT
```

---

## 9. Albums

```
albums
  id UUID PK
  user_id UUID FK -> users.id
  name TEXT
  type TEXT (manual, smart)
  created_at TIMESTAMP
```

---

## 10. Album Items

```
album_items
  album_id UUID FK -> albums.id
  photo_id UUID FK -> photos.id
  added_at TIMESTAMP
```
