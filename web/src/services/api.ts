import axios from 'axios';

export const api = axios.create({
  baseURL: '',        // Vite proxy handles /api → localhost:3000
  timeout: 15_000,   // 15 s global timeout
});

// Redirect to login on 401
api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('psphere_jwt');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export function setAuthToken(token: string) {
  api.defaults.headers.common['Authorization'] = `Bearer ${token}`;
  localStorage.setItem('psphere_jwt', token);
}

export function loadStoredToken() {
  const t = localStorage.getItem('psphere_jwt');
  if (t) api.defaults.headers.common['Authorization'] = `Bearer ${t}`;
  return t;
}

export const Auth = {
  login: (email: string, password: string) =>
    api.post<{ token: string; user: { id: string; email: string } }>('/api/auth/login', { email, password }),
};

export const Photos = {
  timeline: (page = 1, limit = 50) =>
    api.get<{ photos: PhotoRow[]; pagination: Pagination }>('/api/photos/timeline', { params: { page, limit } }),
  get: (id: string) => api.get<{ photo: PhotoDetail }>(`/api/photos/${id}`),
  duplicates: () => api.get<{ groups: DuplicateGroup[]; total: number }>('/api/photos/duplicates'),
};

export const Albums = {
  list:      () => api.get<{ albums: AlbumRow[] }>('/api/albums'),
  get:       (id: string) => api.get<{ album: AlbumRow; photos: PhotoRow[] }>(`/api/albums/${id}`),
  remove:    (id: string) => api.delete(`/api/albums/${id}`),
  generate:  (type: string) => api.post(`/api/albums/smart/${type}`),
  setCover:  (albumId: string, photoId: string) => api.patch(`/api/albums/${albumId}/cover`, { photoId }),
};

export const PhotosBatch = {
  delete: (photoIds: string[]) => api.delete<{ deleted: number }>('/api/photos/batch', { data: { photoIds } }),
};

export const Settings = {
  get:   () => api.get<{ settings: UserSettings; defaults: UserSettings }>('/api/settings'),
  patch: (patch: Partial<UserSettings>) => api.patch<{ settings: UserSettings }>('/api/settings', patch),
  reset: () => api.post<{ settings: UserSettings }>('/api/settings/reset'),
};

export const AI = {
  status:   () => api.get<{ available: boolean; provider: string }>('/api/ai/status'),
  summary:  () => api.get<LibrarySummary>('/api/ai/summary'),
  search:   (query: string) => api.post<SearchResponse>('/api/ai/search', { query }),
  cleanup:  () => api.get<CleanupResult>('/api/ai/cleanup'),
};

export const Devices = {
  list:        () => api.get<{ devices: DeviceRow[] }>('/api/devices'),
  remove:      (id: string) => api.delete(`/api/devices/${id}`),
  toggleSync:  (id: string, enabled: boolean) => api.patch<{ device: DeviceRow }>(`/api/devices/${id}`, { syncEnabled: enabled }),
  rename:      (id: string, name: string) => api.patch<{ device: DeviceRow }>(`/api/devices/${id}`, { name }),
};

export const Sync = {
  check:    (deviceToken: string, hashes: string[]) =>
    api.post<{ missing: string[] }>('/api/sync/check', { deviceToken, hashes }),
  conflicts: () => api.get<{ conflicts: SyncConflict[]; total: number }>('/api/sync/conflicts'),
  resolve:   (conflictId: string, keepFileId: string) =>
    api.post(`/api/sync/conflicts/${conflictId}/resolve`, { keepFileId }),
};

export const Stats = {
  storage:     () => api.get<StorageStats>('/api/stats/storage'),
  syncHistory: (limit = 20) => api.get<{ history: SyncHistoryRow[] }>('/api/stats/sync-history', { params: { limit } }),
};

export const Faces = {
  clusters: () => api.get<{ clusters: FaceCluster[] }>('/api/faces/clusters'),
  rename:   (id: string, personName: string) => api.patch(`/api/faces/clusters/${id}`, { personName }),
  remove:   (id: string) => api.delete(`/api/faces/clusters/${id}`),
};

// ── Types ─────────────────────────────────────────────────────────────────────

export interface PhotoRow     { id: string; taken_at: string | null; thumb_url: string; width: number; height: number; camera?: string }
export interface PhotoDetail  extends PhotoRow { exif: Record<string, unknown>; city?: string; country?: string; tags?: string[]; people?: string[] }
export interface Pagination   { page: number; limit: number; total: number; pages: number }
export interface AlbumRow     { id: string; name: string; type: string; photo_count: number; cover_url: string; created_at: string }
export interface DeviceRow       { id: string; name: string; type: string; sync_enabled: boolean; last_seen: string | null; created_at: string }
export interface SyncConflict    { id: string; path: string; created_at: string; device_a_name: string; modified_a: string; file_a_id: string; device_b_name: string; modified_b: string; file_b_id: string }
export interface StorageDevice   { id: string; name: string; type: string; file_count: number; total_bytes: number }
export interface StorageTotals   { total_files: number; total_bytes: number; photo_count: number }
export interface StorageStats    { devices: StorageDevice[]; totals: StorageTotals }
export interface SyncHistoryRow  { device_name: string; device_type: string; sync_date: string; file_count: number; bytes_synced: number; photo_count: number }
export interface FaceCluster     { id: string; label: string; person_name: string | null; face_count: number; photo_count: number; cover_url: string }

export interface UserSettings {
  ai:            { provider: string; model: string; baseUrl: string };
  storage:       { maxUploadMB: number; dataRootPath: string };
  notifications: { syncComplete: boolean; newDuplicates: boolean; weeklyDigest: boolean };
  vision:        { faceDetectionConfidence: number; objectTagConfidence: number; modelsDir: string };
}
export interface DuplicateGroup { photos: { id: string; taken_at: string | null; thumb_url: string }[]; similarity: number }
export interface LibrarySummary { summary: string; photoCount: number; dateRange: { earliest: string | null; latest: string | null }; topLocations: string[]; topTags: string[] }
export interface CleanupResult  { suggestions: { photoId: string; reason: string; thumb_url: string }[]; summary: string }
export interface SearchResponse { photos: { id: string; taken_at: string | null; thumb_url: string; score: number; tags: string[] }[]; explanation: string; fallback: boolean }
