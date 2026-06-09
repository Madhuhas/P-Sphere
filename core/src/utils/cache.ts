import Redis from 'ioredis';
import { config } from '../config/env';

let client: Redis | null = null;

function getClient(): Redis | null {
  if (client) return client;
  try {
    const redis = new Redis({
      host: config.redis.host,
      port: config.redis.port,
      enableOfflineQueue: false,
      lazyConnect: false,         // connect immediately so first commands don't fail
      retryStrategy: (times) => Math.min(times * 200, 2000),
    });
    redis.on('error', (err) => {
      // Log but keep the client alive — ioredis will reconnect automatically
      console.warn('[cache] Redis error (non-fatal):', err.message);
    });
    client = redis;
    return client;
  } catch {
    return null;
  }
}

export async function cacheGet<T>(key: string): Promise<T | null> {
  try {
    const redis = getClient();
    if (!redis) return null;
    const raw = await redis.get(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export async function cacheSet(key: string, value: unknown, ttlSeconds: number): Promise<void> {
  try {
    const redis = getClient();
    if (!redis) return;
    await redis.set(key, JSON.stringify(value), 'EX', ttlSeconds);
  } catch { /* non-fatal */ }
}

// Use SCAN instead of KEYS to avoid blocking Redis in production
export async function cacheDel(pattern: string): Promise<void> {
  try {
    const redis = getClient();
    if (!redis) return;
    let cursor = '0';
    do {
      const [next, keys] = await redis.scan(cursor, 'MATCH', pattern, 'COUNT', '100');
      cursor = next;
      if (keys.length > 0) await redis.del(...keys);
    } while (cursor !== '0');
  } catch { /* non-fatal */ }
}
