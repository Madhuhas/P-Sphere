# Running & Deploying PSphere

This guide covers every component from local development to a production home-server deployment.

---

## Table of contents

1. [Prerequisites](#1-prerequisites)
2. [Database setup](#2-database-setup)
3. [Backend (Core API)](#3-backend-core-api)
4. [Vision worker](#4-vision-worker)
5. [Web UI](#5-web-ui)
6. [Android app](#6-android-app)
7. [Desktop app](#7-desktop-app)
8. [LLM bridge (Ollama)](#8-llm-bridge-ollama)
9. [Production deployment](#9-production-deployment)
10. [Environment variable reference](#10-environment-variable-reference)
11. [Production checklist](#11-production-checklist)

---

## 1. Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Node.js | 20+ | Backend, vision worker, web UI |
| npm | 10+ | Package management |
| PostgreSQL | 15+ | Primary database |
| Redis | 7+ | Job queue (BullMQ) |
| JDK | 17 | Android build |
| Android Studio | Latest | Android build + emulator |
| Ollama | Latest | Local LLM (optional) |

---

## 2. Database setup

```bash
# Create database and user
psql -U postgres -c "CREATE DATABASE psphere;"
psql -U postgres -c "CREATE USER psphere_user WITH PASSWORD 'your_password';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE psphere TO psphere_user;"
```

Migrations run automatically on backend startup — you don't need to run them manually.

---

## 3. Backend (Core API)

```bash
cd core
```

### Create environment file

```bash
cp .env.example .env
```

Edit `.env`:

```env
PORT=3000
NODE_ENV=development

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=psphere
DB_USER=psphere_user
DB_PASSWORD=your_password

# Auth — change this in production
JWT_SECRET=a_long_random_secret_string_at_least_32_chars
JWT_EXPIRES_IN=7d

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# LLM (optional)
LLM_PROVIDER=ollama
LLM_BASE_URL=http://localhost:11434
LLM_MODEL=llama3

# Storage
DATA_ROOT=./data
MODELS_DIR=./models
```

### Install and run

```bash
npm install

# Development (hot reload)
npm run dev

# Production
npm run build
npm start
```

The API is available at `http://localhost:3000`.  
Health check: `curl http://localhost:3000/api/health`

---

## 4. Vision worker

```bash
cd vision-worker
cp .env.example .env
```

Edit `.env` — only Redis and DB settings are needed:

```env
REDIS_HOST=localhost
REDIS_PORT=6379

DB_HOST=localhost
DB_PORT=5432
DB_NAME=psphere
DB_USER=psphere_user
DB_PASSWORD=your_password
```

### ONNX models

Place model files in `vision-worker/models/`:

```
models/
├── face_detection.onnx      # e.g. YOLOv8-face or similar
├── face_embedding.onnx      # e.g. ArcFace 512-d
└── object_tagging.onnx      # e.g. MobileNetV3 ImageNet
```

### Run

```bash
npm install
npm run dev        # development
npm run build && npm start   # production
```

The worker connects to Redis and starts processing jobs from the `photo.process` and `photo.extract_exif` queues.

---

## 5. Web UI

```bash
cd web
npm install

# Development (Vite dev server)
npm run dev
```

Web UI is at `http://localhost:5173`.

### Production build

```bash
npm run build
# Output in web/dist/ — serve with nginx or any static server
```

Example nginx config:

```nginx
server {
    listen 80;
    server_name your-server.local;

    root /path/to/psphere/web/dist;
    index index.html;

    # SPA routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Proxy API requests to Core backend
    location /api/ {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

---

## 6. Android app

### Requirements

- Android Studio (latest stable)
- JDK 17
- Android device or emulator running API 26+

### Configure the server URL

Open `mobile/app/build.gradle.kts` and set `BASE_URL` to your Core server's LAN IP:

```kotlin
defaultConfig {
    buildConfigField("String", "BASE_URL", "\"http://192.168.1.100:3000\"")
}
```

Replace `192.168.1.100` with the actual LAN IP of the machine running the Core backend.

For the emulator connecting to localhost, use `http://10.0.2.2:3000`.

### Build and install

```bash
# Debug APK
./gradlew assembleDebug
# APK at: mobile/app/build/outputs/apk/debug/app-debug.apk

# Install directly to connected device
./gradlew installDebug

# Release APK (requires signing config)
./gradlew assembleRelease
```

Or open the project in Android Studio and click **Run**.

### P2P sync

P2P sync starts automatically when the app launches. Both phones must be on the same WiFi network. No configuration needed — they discover each other via mDNS.

---

## 7. Desktop app

```bash
cd desktop
npm install
```

### Development

```bash
npm run dev
```

This starts the Electron app pointing at `http://localhost:5173` (the Vite dev server must be running).

### Production build

```bash
npm run build
# Platform-specific installer in desktop/dist/
```

The desktop app connects to the same Core backend as the mobile app. Configure the server URL in the app's settings panel.

---

## 8. LLM bridge (Ollama)

AI features (search, auto-albums, summaries) require a running LLM. Ollama is the simplest option.

```bash
# Install Ollama (Linux/macOS)
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull llama3          # recommended — good balance of speed and quality
ollama pull llama3:8b       # faster, lighter

# Start server (runs on port 11434 by default)
ollama serve
```

To use LM Studio instead, set:

```env
LLM_PROVIDER=lmstudio
LLM_BASE_URL=http://localhost:1234
LLM_MODEL=<model-name-in-lmstudio>
```

Any OpenAI-compatible API works with `LLM_PROVIDER=openai-compat`.

AI features degrade gracefully — if the LLM is unavailable, search falls back to keyword scoring and summaries return raw metadata.

---

## 9. Production deployment

### Recommended setup: single home server (mini-PC / NAS / Raspberry Pi 5)

All services run on one machine. Phones and desktop connect over LAN.

```
Home server (192.168.1.100)
├── PostgreSQL     :5432
├── Redis          :6379
├── Core API       :3000
├── Vision Worker  (background process)
└── Web UI         :80 (nginx, serving /dist + proxying /api)

Phones + desktop  → connect to 192.168.1.100:3000
```

### Process management (systemd)

Create a service file for the Core API at `/etc/systemd/system/psphere-core.service`:

```ini
[Unit]
Description=PSphere Core API
After=network.target postgresql.service redis.service

[Service]
Type=simple
User=psphere
WorkingDirectory=/opt/psphere/core
EnvironmentFile=/opt/psphere/core/.env
ExecStart=/usr/bin/node dist/index.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Repeat for `psphere-vision.service` (vision worker).

```bash
sudo systemctl daemon-reload
sudo systemctl enable psphere-core psphere-vision
sudo systemctl start psphere-core psphere-vision
```

### Process management (PM2)

Alternatively, use PM2:

```bash
npm install -g pm2

# From repo root
pm2 start core/dist/index.js --name psphere-core
pm2 start vision-worker/dist/index.js --name psphere-vision
pm2 save
pm2 startup
```

### Data directory

By default, files land in `core/data/`. For persistent storage, set `DATA_ROOT` to a path on a separate drive:

```env
DATA_ROOT=/mnt/storage/psphere
```

Create the directory and give the service user write access:

```bash
sudo mkdir -p /mnt/storage/psphere
sudo chown psphere:psphere /mnt/storage/psphere
```

---

## 10. Environment variable reference

### Core API (`core/.env`)

| Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | HTTP port |
| `NODE_ENV` | `development` | Set to `production` in prod |
| `JWT_SECRET` | _(required in prod)_ | Secret for signing JWTs — must be set |
| `JWT_EXPIRES_IN` | `7d` | Token lifetime |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `psphere` | Database name |
| `DB_USER` | `postgres` | Database user |
| `DB_PASSWORD` | _(empty)_ | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `LLM_PROVIDER` | `ollama` | `ollama` / `lmstudio` / `openai-compat` |
| `LLM_BASE_URL` | `http://localhost:11434` | LLM API base URL |
| `LLM_MODEL` | `llama3` | Model name |
| `LLM_API_KEY` | _(empty)_ | API key (openai-compat only) |
| `DATA_ROOT` | `./data` | Root directory for uploaded files |
| `CORS_ORIGIN` | _(any)_ | Restrict CORS to this origin in production |

### Vision worker (`vision-worker/.env`)

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `psphere` | Database name |
| `DB_USER` | `postgres` | Database user |
| `DB_PASSWORD` | _(empty)_ | Database password |
| `MODELS_DIR` | `./models` | Path to ONNX model files |

### Android app (`mobile/app/build.gradle.kts`)

| Field | Default | Description |
|---|---|---|
| `BASE_URL` | `http://10.0.2.2:3000` | Core API base URL (emulator default) |

---

## 11. Production checklist

Before going live:

- [ ] `JWT_SECRET` is set to a long random string (not the default)
- [ ] `NODE_ENV=production` is set — triggers env var validation at startup
- [ ] `DB_PASSWORD` is set and the database user has minimal permissions
- [ ] `CORS_ORIGIN` is set to the web UI's exact origin
- [ ] `DATA_ROOT` points to a path with sufficient disk space
- [ ] PostgreSQL and Redis are not exposed to the public internet (bind to localhost or LAN)
- [ ] Core API is behind a reverse proxy (nginx) — do not expose Node directly
- [ ] HTTPS is configured on nginx (Let's Encrypt or self-signed for LAN)
- [ ] Firewall blocks ports 5432 and 6379 from outside the LAN
- [ ] `npm run build` is used for production (not `npm run dev`)
- [ ] ONNX model files are present in the vision worker's `models/` directory
- [ ] Ollama is running (if AI features are needed)
- [ ] Systemd / PM2 is configured to auto-restart services on failure
- [ ] A backup strategy is in place for `DATA_ROOT` and the PostgreSQL database

---

## Troubleshooting

### Backend fails to start: `connect ECONNREFUSED ::1:5432`
PostgreSQL is not running or is bound to a different address.  
Check: `sudo systemctl status postgresql` and verify `DB_HOST` in `.env`.

### EXIF jobs not processing
The vision worker is not running or cannot reach Redis.  
Check: `redis-cli ping` and verify `REDIS_HOST` / `REDIS_PORT` in both `.env` files.

### Thumbnails not generating
`sharp` requires native binaries. Run `npm rebuild sharp` in the `core/` directory.

### Android app cannot reach the server
Verify `BASE_URL` in `build.gradle.kts` uses the server's LAN IP (not `localhost`).  
The server must be reachable from the device's network — not just from the build machine.

### P2P sync not discovering peers
Both devices must be on the same WiFi network with multicast enabled.  
Some corporate/guest WiFi networks block mDNS — use a home/personal router.

### LLM features not working
Check Ollama is running: `curl http://localhost:11434/api/tags`  
Verify `LLM_MODEL` matches an installed model: `ollama list`
