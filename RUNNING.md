# RUNNING.md (P-Sphere)

This document contains step-by-step commands to run the system.

## 0) Prerequisites
- Node.js 18+ (recommended)
- npm
- PostgreSQL (running)
- (Optional) Redis (for queues and cache)
- `core/.env` copied from `core/.env.example`

## 1) Backend (core)
### 1.1 Install dependencies
From repo root:
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere"
cd core
npm install
```

### 1.2 Create environment file
Copy and edit:
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/core"
copy .env.example .env
```
Set at minimum:
- `PORT`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET`
- `REDIS_HOST`, `REDIS_PORT`
- `LLM_BASE_URL` / `LLM_PROVIDER` / `LLM_MODEL` if using AI features

### 1.3 Run backend (development)
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/core"
npm run dev
```
Expected output:
- server starts without crash
- backend listens on `http://localhost:<PORT>`
- `GET /api/health` responds with JSON

## 2) Web UI (web)
### 2.1 Install dependencies
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/web"
npm install
```

### 2.2 Run web UI
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/web"
npm run dev
```
Open the printed URL (typically `http://localhost:5173`).

## 3) Vision worker (vision-worker) — OPTIONAL
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/vision-worker"
npm install
npm run dev
```
If `vision-worker` is not required, skip this step.

## 4) LLM bridge / local AI endpoint — OPTIONAL
The backend uses `core/src/services/llm.service.ts` to call a local LLM endpoint.
If you want AI search/chat features, ensure the values in `core/.env` match your LLM provider.

Typical default values from `core/.env.example`:
- `LLM_PROVIDER=ollama`
- `LLM_BASE_URL=http://localhost:11434`
- `LLM_MODEL=llama3`
- `LLM_API_KEY=` (optional)

Then start the backend as above.

## 5) Android app (mobile)
### 5.1 Open in Android Studio
- Open Android Studio
- Choose **Open an existing project**
- Select the folder: `mobile/`

### 5.2 Run on emulator or real device
- Start an Android emulator or connect a USB device
- Use Android Studio's Run action to launch the app

## 6) Run everything together
In separate terminals:
1) Backend:
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/core"
npm run dev
```
2) Web UI:
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/web"
npm run dev
```
3) Optional vision worker:
```bat
cd "c:/Users/dell/Desktop/M/Pro/P Sphere/PSphere/vision-worker"
npm run dev
```

## 7) Troubleshooting basics
- Backend fails: verify `core/.env` values and ensure PostgreSQL is running on the configured host/port.
- Redis problems: verify `REDIS_HOST` and `REDIS_PORT` in `core/.env`.
- Web cannot reach backend: verify the API base URL in `web/src/services/api.ts` or the frontend config.
- AI endpoint fails: ensure the LLM provider URL is reachable and correct in `core/.env`.
- Android app does not run: sync Gradle in Android Studio and confirm the emulator/device is available.

