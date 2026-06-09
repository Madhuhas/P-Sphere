# PSphere

A self-hosted personal photo platform. Sync, organise, and search your photos across every device — with local AI — and no cloud account required.

---

## What it does

PSphere replaces Google Photos / iCloud with infrastructure you own. Photos sync from your phone and desktop to a central home server. A local AI pipeline then tags faces, objects, and scenes — and a local LLM lets you search your library in natural language.

On top of that, the Android app includes a **peer-to-peer sync layer**: two phones on the same WiFi can sync photos directly with no server in between.

---

## Architecture

```
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│   Android App    │   │   Desktop App    │   │     Web UI       │
│  (Kotlin/Compose)│   │ (Electron/Tauri) │   │  (React + Vite)  │
└────────┬─────────┘   └────────┬─────────┘   └────────┬─────────┘
         │                      │  HTTP/REST             │
         │          ┌───────────┴────────────┐          │
         └──────────►      Core API          ◄──────────┘
    P2P (mDNS+TCP)  │  Express + TypeScript  │
                    └──────┬────────┬────────┘
                           │        │
               ┌───────────▼┐  ┌────▼──────┐  ┌────────────┐
               │ PostgreSQL │  │   Redis   │  │  /data     │
               │  (schema)  │  │ (queues)  │  │  (files)   │
               └────────────┘  └─────┬─────┘  └────────────┘
                                     │
                          ┌──────────▼──────────┐
                          │   Vision Worker      │
                          │  ONNX face + objects │
                          └──────────┬───────────┘
                                     │
                          ┌──────────▼──────────┐
                          │   Local LLM          │
                          │  Ollama / LM Studio  │
                          └─────────────────────┘
```

### Sync modes

| Mode | How | Requires |
|---|---|---|
| **P2P LAN** | mDNS discovery + direct TCP | Same WiFi, no server |
| **Core** | Hash-based upload to API server | Core backend running |
| **Both** | Runs simultaneously | — |

---

## Features

### Sync
- SHA-256 hash-based delta sync — only changed files are transferred
- Phone-to-phone sync over LAN via mDNS (no server needed)
- Android background sync with battery and network constraints
- Desktop folder sync
- Conflict detection and resolution UI
- Automatic retry with exponential backoff

### Photo pipeline
- EXIF metadata extraction (date, GPS, camera model)
- 256px and 1024px WebP thumbnail generation
- Perceptual hash (dHash) for duplicate detection
- CDN-ready thumbnail serving with immutable cache headers + ETags
- Queued batch processing via BullMQ

### AI (all local, nothing leaves your network)
- Face detection with ONNX models
- 512-dimensional face embeddings + HDBSCAN clustering
- Object and scene tagging
- Natural language photo search via local LLM
- Auto-album creation (Trips, People, Pets, Places)
- Library summaries and cleanup suggestions

### Web UI
- Virtualised infinite-scroll photo timeline
- Map view with GPS clusters
- Album management
- Admin dashboard (storage, sync history, face clusters)
- Dark mode with persisted preference
- Offline banner, skeleton loaders, toast notifications

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Node.js 20, TypeScript, Express 5, PostgreSQL 15, Redis 7 |
| Queue | BullMQ |
| Vision AI | ONNX Runtime Node.js |
| LLM | Ollama / LM Studio (any OpenAI-compatible endpoint) |
| Web UI | React 19, Vite, Tanstack Virtual, Axios |
| Android | Kotlin, Jetpack Compose, Retrofit, WorkManager, DataStore |
| Desktop | Electron / Tauri |
| Auth | JWT (Bearer tokens) |
| Storage | Local disk, WebP thumbnails via Sharp |

---

## Project structure

```
PSphere/
├── core/               Node.js backend API
│   └── src/
│       ├── routes/     HTTP endpoints
│       ├── services/   Business logic
│       ├── db/         Migrations + connection pool
│       ├── queue/      BullMQ producers + EXIF worker
│       ├── config/     Env vars + storage paths
│       ├── middleware/ JWT auth
│       └── utils/      Cache (Redis), LLM JSON parsing
│
├── web/                React frontend
│   └── src/
│       ├── pages/      Timeline, Albums, Search, Admin, Settings
│       ├── components/ Navigation, PhotoModal, ErrorBoundary, etc.
│       ├── services/   Axios API client + TypeScript types
│       └── context/    Theme, Toast
│
├── vision-worker/      Background AI processing
│   └── src/
│       ├── queue/      BullMQ worker + queue config
│       ├── models/     Face detection, embedding, clustering, tagging
│       └── utils/      DB access, image preprocessing
│
├── mobile/             Android app (Kotlin)
│   └── app/src/main/java/com/psphere/
│       ├── p2p/        Peer discovery (mDNS), TCP server, sync session
│       ├── sync/       SyncManager, SyncWorker, SyncOrchestrator, FileHasher
│       ├── network/    Retrofit API client
│       ├── data/       DataStore (JWT, device tokens)
│       ├── ui/         Home, PhotoViewer, Register screens
│       └── navigation/ Compose navigation
│
├── desktop/            Desktop sync app (Electron/Tauri)
│
├── ROADMAP.md          Feature roadmap with completion status
├── ARCHITECTURE.md     Detailed architecture notes
├── API_SPEC.md         REST API reference
├── DATA_MODEL.md       Database schema reference
├── VISION_PIPELINE.md  AI pipeline documentation
└── DEPLOY.md           How to run and deploy (this repo)
```

---

## Quick start (development)

### Prerequisites
- Node.js 20+
- PostgreSQL 15+
- Redis 7+
- Android Studio (for mobile)
- Ollama (optional, for AI features)

### 1 — Backend

```bash
cd core
cp .env.example .env        # fill in DB creds and JWT_SECRET
npm install
npm run dev
```

Backend runs on `http://localhost:3000`.

### 2 — Vision worker

```bash
cd vision-worker
cp .env.example .env        # same Redis settings
npm install
npm run dev
```

### 3 — Web UI

```bash
cd web
npm install
npm run dev
```

Web UI runs on `http://localhost:5173`.

### 4 — Android app

1. Open `mobile/` in Android Studio
2. In `mobile/app/build.gradle.kts`, set `BASE_URL` to your machine's LAN IP:
   ```kotlin
   buildConfigField("String", "BASE_URL", "\"http://192.168.1.x:3000\"")
   ```
3. Build and run on a device or emulator

### 5 — Ollama (optional AI)

```bash
ollama serve
ollama pull llama3
```

---

## API overview

Full spec in [API_SPEC.md](API_SPEC.md). Key endpoints:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Health check |
| `POST` | `/api/auth/register` | Register user |
| `POST` | `/api/auth/login` | Login, returns JWT |
| `POST` | `/api/devices/register` | Register a device |
| `POST` | `/api/sync/check` | Hash check (which files are missing) |
| `POST` | `/api/sync/upload` | Upload a file |
| `GET` | `/api/photos/timeline` | Paginated photo timeline |
| `GET` | `/api/photos/locations` | GPS clusters for map |
| `GET` | `/api/photos/thumb/:id/:size` | Serve thumbnail (sm/lg) |
| `POST` | `/api/ai/search` | Natural language photo search |
| `GET` | `/api/ai/summary` | AI-generated library summary |
| `POST` | `/api/ai/album/create` | Auto-create albums via LLM |

---

## Environment variables

See [DEPLOY.md](DEPLOY.md) for the full reference. Key vars:

| Variable | Default | Required in prod |
|---|---|---|
| `JWT_SECRET` | `change_me_in_production` | Yes |
| `DB_HOST` | `localhost` | Yes |
| `DB_NAME` | `psphere` | Yes |
| `DB_USER` | `postgres` | Yes |
| `DB_PASSWORD` | _(empty)_ | Yes |
| `REDIS_HOST` | `localhost` | No |
| `LLM_PROVIDER` | `ollama` | No |
| `LLM_BASE_URL` | `http://localhost:11434` | No |
| `LLM_MODEL` | `llama3` | No |

---

## Roadmap

See [ROADMAP.md](ROADMAP.md) for the full build plan. Status summary:

| Phase | What | Status |
|---|---|---|
| 1–8 | Core backend, Android app, sync engine, AI pipeline, web UI | Done |
| 10 | P2P sync, polish, performance tuning | Done |
| 9 | Multi-user, encrypted vaults, multi-node, external storage | Planned |

---

## P2P sync (KotiniSync)

The `KotiniSync/` directory contains the standalone P2P sync prototype that was merged into the main Android app. The mDNS discovery and TCP transfer layers from that project now power the `com.psphere.p2p` package.

Internet P2P (WebRTC + Firebase signaling) was prototyped in KotiniSync but is not yet wired up in the main app — that's the next phase.

---

## License

Private / all rights reserved.
