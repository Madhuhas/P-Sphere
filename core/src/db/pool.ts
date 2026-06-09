import { Pool } from 'pg';
import { config } from '../config/env';

export const pool = new Pool({
  host:                   config.db.host,
  port:                   config.db.port,
  database:               config.db.name,
  user:                   config.db.user,
  password:               config.db.password,
  max:                    20,
  idleTimeoutMillis:      30_000,
  connectionTimeoutMillis: 5_000,
});

// Log the error but do NOT exit — let the request that triggered it fail naturally
// and allow the pool to recover by opening new connections on the next request.
pool.on('error', (err) => {
  console.error('[db] Unexpected pool error (non-fatal):', err.message);
});
