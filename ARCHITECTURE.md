# P‑Sphere Architecture
A personal cloud OS for sync, photos, and local AI.

## 1. Overview
P‑Sphere is a self‑hosted personal cloud platform that unifies:
- Device sync  
- Photo library  
- Local AI (vision + LLM)  
- Privacy‑first storage  

Runs on:
- Phones  
- Tablets  
- Desktop  
- Local Core node (mini‑PC/NAS)

## 2. High‑Level Architecture

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│ Android App │     │ Desktop App │     │  Web UI      │
└──────┬──────┘     └──────┬──────┘     └──────┬───────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │ HTTP / REST
                    ┌──────▼──────┐
                    │  Core API   │  Express + TypeScript
                    └──────┬──────┘
          ┌────────────────┼────────────────┐
          │                │                │
   ┌──────▼─────┐  ┌───────▼──────┐  ┌─────▼──────┐
   │  Postgres  │  │    Redis     │  │  Storage   │
   │  (schema)  │  │  (job queue) │  │  /data     │
   └────────────┘  └───────┬──────┘  └────────────┘
                           │
                  ┌────────▼────────┐
                  │  Vision Worker  │  ONNX / Python
                  └────────┬────────┘
                           │
                  ┌────────▼────────┐
                  │   LLM Bridge    │  Ollama / LM Studio
                  └─────────────────┘
```

## 3. Components

| Component       | Role                                              |
|-----------------|---------------------------------------------------|
| Backend         | REST API, auth, device mgmt, file indexing        |
| Sync Core       | Hash‑based delta sync from devices                |
| Media Service   | EXIF extraction, thumbnail generation             |
| Vision Worker   | Face detection, embeddings, clustering, tagging   |
| LLM Bridge      | Chat, semantic search, auto‑album creation        |
| Postgres        | Primary relational store                          |
| Redis           | Async job queue for vision pipeline               |
| Storage         | Raw files, thumbnails, AI models on local disk    |

## 4. Data Flow

### Photo Ingestion
```
Device → POST /sync/upload → Backend → Save file → Push job to Redis
→ Vision Worker: EXIF → Face detection → Embeddings → Tags → DB writes
```

### AI Search
```
User query → POST /ai/search → LLM Bridge → Query DB candidates
→ LLM ranks → Return ordered photo list
```

### Face Clustering
```
Vision Worker → Generate 512‑dim embeddings → HDBSCAN clustering
→ Create / update face_clusters → Link to photos
```

### Sync
```
Device scans files → SHA‑256 hash each → POST hashes to /sync/check
→ Backend returns missing hashes → Device uploads only changed files
```

## 5. Deployment

### Single‑Node
All services run on one machine (mini‑PC / NAS).

### Multi‑Node
- **Node A** — Core API, Postgres, Redis, Storage  
- **Node B** — Vision Worker + LLM runtime (GPU box)

## 6. Roadmap
- Smart albums  
- Duplicate detection  
- Web UI  
