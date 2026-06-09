# Deployment Guide

---

# 1. Requirements
- Docker  
- Docker Compose  
- Postgres  
- Redis  
- Ollama or LM Studio  

---

# 2. Directory Layout

```
/data
  /photos
  /files
  /thumbs
  /models
```

---

# 3. Environment Variables

```
PORT=4000
DATABASE_URL=postgres://...
JWT_SECRET=...
REDIS_URL=redis://...
```

---

# 4. Running the Core

```bash
docker compose up -d
```

Services started:
- Backend  
- Postgres  
- Redis  
- Vision Worker  
- LLM Bridge  

---

# 5. Running Mobile App
Open `/mobile` in Android Studio.  
Set the backend URL in `app/src/main/res/values/config.xml`.

---

# 6. Running Desktop App
```bash
cd desktop
npm install
npm run dev
```

---

# 7. Multi‑Node Deployment

## Node A (Core)
- Backend  
- DB  
- Redis  
- Storage  

## Node B (AI Box)
- Vision Worker  
- LLM runtime  

Set `VISION_WORKER_URL` and `LLM_BRIDGE_URL` on Node A to point to Node B.

---

# 8. Backups
- **Postgres:** `pg_dump psphere > backup.sql`  
- **Files:** rsync or snapshot the `/data` folder  
- Recommended: nightly cron for both  
