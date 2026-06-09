# P‑Sphere Roadmap  
A step‑by‑step build plan for the entire system.

This roadmap is designed for incremental development.  
Each phase is self‑contained and can be executed independently.

---

# 1. Phase 1 — Core Backend Foundation (DONE)

## 1.1 Backend Setup (DONE)
- Project scaffold  
- Express + TypeScript  
- Postgres connection  
- JWT auth  
- Users + Devices tables  
- Auth routes  
- Device routes  

## 1.2 Health Check API (DONE)
- [x] GET /api/health  
- [x] Return `{ status: "ok" }`  
- [x] Used by mobile app to verify backend availability  

## 1.3 Device Registration API (DONE)
- [x] POST /api/devices/register  
- [x] Accept: name, type  
- [x] Return: deviceId + token  
- [x] Save device in DB  
- [x] Protect with JWT  

---

# 2. Phase 2 — Android App (APK) (DONE)

## 2.1 Project Setup (DONE)
- [x] Create Kotlin + Jetpack Compose project  
- [x] Add Retrofit client  
- [x] Add DataStore for token  
- [x] Add navigation scaffold  

## 2.2 Register Device Flow (DONE)
- [x] RegisterDeviceScreen  
- [x] Call GET /api/health  
- [x] Call POST /api/devices/register  
- [x] Save token  
- [x] Navigate to HomeScreen  

## 2.3 Basic Home Screen (DONE)
- [x] Show device info  
- [x] Show sync status  
- [x] Button: "Start Sync"  

---

# 3. Phase 3 — Sync Engine (Android + Backend) (DONE)

## 3.1 File Hashing (DONE)
- [x] SHA‑256 hashing  
- [x] Compare with backend  
- [x] Skip unchanged files  

## 3.2 Upload API (DONE)
- [x] POST /api/sync/upload  
- [x] Multipart upload  
- [x] Fields: file, path, hash, modifiedAt  

## 3.3 Android SyncService (DONE)
- [x] Background worker  
- [x] Queue uploads  
- [x] Retry logic  
- [x] Battery/network constraints  

---

# 4. Phase 4 — Photo Ingestion Pipeline (Backend) (DONE)

## 4.1 EXIF Extraction (DONE)
- [x] Extract: takenAt, GPS, camera  
- [x] Store in `photos` table  

## 4.2 Thumbnail Generation (DONE)
- [x] Create 256px + 1024px versions  
- [x] Save to `/data/thumbs`  

## 4.3 Timeline API (DONE)
- [x] GET /api/photos/timeline  
- [x] Return sorted photos  

## 4.4 Map API (DONE)
- [x] GET /api/photos/locations  
- [x] Return GPS clusters  

---

# 5. Phase 5 — Vision AI Worker (DONE)

## 5.1 Worker Setup (DONE)
- [x] Python or Node worker  
- [x] Connect to Redis queue  
- [x] Pull "photo.process" jobs  

## 5.2 Face Detection (DONE)
- [x] ONNX model  
- [x] Store bounding boxes  

## 5.3 Face Embeddings (DONE)
- [x] Generate embeddings  
- [x] Save to DB  

## 5.4 Face Clustering (DONE)
- [x] K‑means or HDBSCAN  
- [x] Create `face_clusters`  

## 5.5 Object + Scene Tagging (DONE)
- [x] Lightweight ONNX model  
- [x] Save tags to DB  

---

# 6. Phase 6 — Local LLM Integration (DONE)

## 6.1 LLM Bridge Service (DONE)
- [x] Connect to Ollama / LM Studio  
- [x] Chat endpoint  
- [x] Photo search endpoint  

## 6.2 AI Search (DONE)
- [x] Query DB for candidates  
- [x] LLM ranks results  
- [x] Return ordered list  

## 6.3 Automation Tasks (DONE)
- [x] Auto‑album creation  
- [x] Summaries  
- [x] Cleanup suggestions  

---

# 7. Phase 7 — Desktop App (DONE)

## 7.1 Project Setup (DONE)
- [x] Tauri/Electron app  
- [x] Folder sync  
- [x] Admin console  

## 7.2 Sync Integration (DONE)
- [x] Same hashing logic  
- [x] Same upload API  

---

# 8. Phase 8 — Polishing & OS Features (DONE)

## 8.1 Smart Albums (DONE)
- [x] Trips  
- [x] People  
- [x] Pets  
- [x] Places  

## 8.2 Duplicate Detection (DONE)
- [x] Perceptual hashing  
- [x] Similarity scoring  

## 8.3 Web UI (DONE)
- [x] Photo viewer  
- [x] Admin dashboard  

---

# 9. Phase 9 — Future Enhancements

- Multi‑user support  
- Encrypted vaults  
- Multi‑node replication  
- Offline AI packs  
- External storage adapters (S3, SMB, HDD)  

---

# 10. Phase 10 — Polishing & UX Enhancements

## 10.1 Web UI Polish (DONE)
- [x] Page transition animations (fade / slide between routes)  
- [x] Photo grid hover effects and selection mode  
- [x] Dark mode toggle with persisted preference  
- [x] Skeleton loading states for timeline and albums  
- [x] Toast notification system for async actions  
- [x] Responsive layout for tablets and small screens  

## 10.2 Mobile App Polish (DONE)
- [x] Animated sync progress bar with file-by-file feedback  
- [x] Full-screen photo viewer with pinch-to-zoom  
- [x] Swipe navigation between photos  
- [x] Pull-to-refresh on Home screen  
- [x] Haptic feedback on sync complete / error  
- [x] Splash screen and app icon  

## 10.3 Admin Dashboard Improvements (DONE)
- [x] Storage usage chart (per device, per album, total)  
- [x] Sync history log with timestamps and file counts  
- [x] Face cluster management UI (rename, merge, delete)  
- [x] Album cover photo picker  
- [x] Batch delete photos from cleanup suggestions  

## 10.4 Error States & Offline Handling (DONE)
- [x] Offline banner in web UI when backend is unreachable  
- [x] Retry UI for failed sync jobs (Android + desktop)  
- [x] Graceful degradation when LLM is offline  
- [x] 404 and 500 error pages in web UI  
- [x] Connection timeout feedback in mobile app  

## 10.5 Device‑to‑Device Sync UI (DONE)
- [x] List all devices and their last-seen status  
- [x] Per-device sync enable/disable toggle  
- [x] Conflict resolution UI for files modified on multiple devices  
- [x] Cross-device sync progress visible on all clients  

## 10.6 Settings Page (DONE)
- [x] AI settings: LLM provider, model, base URL  
- [x] Storage settings: data root path, max upload size  
- [x] Device settings: rename device, revoke token  
- [x] Notification preferences (sync complete, new duplicates found)  
- [x] Vision worker settings: model paths, confidence thresholds  

## 10.7 Performance Tuning
- [ ] Postgres indexes on `taken_at`, `phash`, `device_id`  
- [ ] Redis caching for `/api/photos/timeline` and `/api/ai/summary`  
- [ ] Lazy loading and virtualised scroll in web timeline  
- [x] Batch EXIF extraction (process multiple photos per job)  
- [ ] CDN-ready static asset serving for thumbnails  

---

# 11. Quick Navigation

- **[Architecture](ARCHITECTURE.md)**  
- **[Data Model](DATA_MODEL.md)**  
- **[Vision Pipeline](VISION_PIPELINE.md)**  
- **[API Spec](API_SPEC.md)**  
- **[Deployment Guide](DEPLOYMENT.md)**
