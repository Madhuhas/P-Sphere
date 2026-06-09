# API Specification

Base URL:
```
/api
```

---

# 1. Auth

## POST /auth/register
Body:
```json
{ "email": "user@example.com", "password": "..." }
```
Response `201`:
```json
{ "user": { "id": "...", "email": "...", "created_at": "..." } }
```

## POST /auth/login
Body:
```json
{ "email": "user@example.com", "password": "..." }
```
Response `200`:
```json
{ "token": "...", "user": { "id": "...", "email": "..." } }
```

---

# 2. Devices

## POST /devices/register
Body:
```json
{ "name": "Pixel 8", "type": "android" }
```
Response `201`:
```json
{ "deviceId": "...", "token": "..." }
```

## GET /devices
Response `200`:
```json
{ "devices": [ { "id": "...", "name": "...", "platform": "...", "registered_at": "..." } ] }
```

## DELETE /devices/:id
Response `200`:
```json
{ "message": "Device removed" }
```

---

# 3. Sync

## POST /sync/upload
Multipart form:
- `file` — binary  
- `path` — relative file path on device  
- `hash` — SHA‑256 hex string  
- `modifiedAt` — ISO timestamp  

Response `201`:
```json
{ "fileId": "..." }
```

## POST /sync/check
Body:
```json
{ "hashes": ["abc123", "def456"] }
```
Response `200`:
```json
{ "missing": ["def456"] }
```

---

# 4. Photos

## GET /photos/timeline
Query params: `page`, `limit`, `from`, `to`  
Response `200`:
```json
{ "photos": [ { "id": "...", "taken_at": "...", "thumb_url": "..." } ] }
```

## GET /photos/:id
Response `200`:
```json
{ "photo": { "id": "...", "taken_at": "...", "exif": {}, "tags": [], "faces": [] } }
```

## GET /photos/locations
Response `200`:
```json
{ "locations": [ { "lat": 0.0, "lon": 0.0, "count": 12 } ] }
```

---

# 5. AI

## POST /ai/chat
Body:
```json
{ "message": "Show me photos from my trip to Goa" }
```
Response `200`:
```json
{ "reply": "...", "photos": [] }
```

## POST /ai/search
Body:
```json
{ "query": "sunset beach" }
```
Response `200`:
```json
{ "photos": [ { "id": "...", "score": 0.95 } ] }
```

## POST /ai/album/create
Body:
```json
{ "name": "Trip to Goa", "query": "Goa 2024" }
```
Response `201`:
```json
{ "album": { "id": "...", "name": "..." } }
```

---

# 6. Health

## GET /health
Response `200`:
```json
{ "status": "ok", "timestamp": "..." }
```
