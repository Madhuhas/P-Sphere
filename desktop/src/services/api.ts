import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:3000';

export const api = axios.create({ baseURL: BASE_URL });

export function setAuthToken(token: string) {
  api.defaults.headers.common['Authorization'] = `Bearer ${token}`;
}

export function clearAuthToken() {
  delete api.defaults.headers.common['Authorization'];
}

// ── Typed wrappers ─────────────────────────────────────────────────────────────

export const Auth = {
  login:    (email: string, password: string) =>
    api.post<{ token: string; user: { id: string; email: string } }>('/api/auth/login', { email, password }),
};

export const Devices = {
  list:     () => api.get<{ devices: DeviceRow[] }>('/api/devices'),
  remove:   (id: string) => api.delete(`/api/devices/${id}`),
};

export const Sync = {
  check:    (deviceToken: string, hashes: string[]) =>
    api.post<{ missing: string[] }>('/api/sync/check', { deviceToken, hashes }),
};

export const AI = {
  status:   () => api.get<{ available: boolean; provider: string }>('/api/ai/status'),
  summary:  () => api.get<LibrarySummary>('/api/ai/summary'),
  cleanup:  () => api.get<CleanupResult>('/api/ai/cleanup'),
  createAlbums: () => api.post<AutoAlbumResult>('/api/ai/album/create'),
};

export const Photos = {
  timeline: (page = 1, limit = 50) =>
    api.get<{ photos: PhotoRow[]; pagination: Pagination }>('/api/photos/timeline', { params: { page, limit } }),
};

// ── Shared types ──────────────────────────────────────────────────────────────

export interface DeviceRow  { id: string; name: string; type: string; last_seen: string | null; created_at: string }
export interface PhotoRow   { id: string; taken_at: string | null; thumb_url: string; width: number; height: number }
export interface Pagination { page: number; limit: number; total: number; pages: number }

export interface LibrarySummary {
  summary: string;
  photoCount: number;
  dateRange: { earliest: string | null; latest: string | null };
  topLocations: string[];
  topTags: string[];
}

export interface CleanupSuggestion { photoId: string; reason: string; thumb_url: string }
export interface CleanupResult     { suggestions: CleanupSuggestion[]; summary: string }
export interface AutoAlbumResult   { created: { albumId: string; name: string; photoCount: number }[]; explanation: string }
