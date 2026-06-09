# Vision Pipeline

The Vision Worker processes photos asynchronously using a Redis queue.

---

## 1. Pipeline Overview

```
New Photo → Queue Job → Vision Worker → DB Writes → UI
```

---

## 2. Steps

### 2.1 EXIF Extraction
- Extract takenAt, GPS, camera  
- Save to `photos` + `photo_locations`

### 2.2 Face Detection
- ONNX model  
- Output bounding boxes  

### 2.3 Face Embeddings
- Generate 512‑dim vector  
- Save to `faces.embedding_vector`

### 2.4 Face Clustering
- HDBSCAN or K‑means  
- Create/update `face_clusters`

### 2.5 Object + Scene Tagging
- Lightweight ONNX model  
- Save tags to `photo_tags`

### 2.6 Place Tagging
- Reverse geocode GPS  
- Offline DB (OpenStreetMap extract)

---

## 3. Worker Architecture

```
vision-worker/
  worker.ts
  models/
  utils/
  queue/
```

---

## 4. Queue Jobs

```
photo.process
photo.generate_thumbs
photo.extract_exif
```

---

## 5. Performance Notes
- Use ONNX Runtime  
- Use TensorRT if GPU available  
- Batch embeddings for speed  
