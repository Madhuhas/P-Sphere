import { Pool } from 'pg';

export const pool = new Pool({
  host:     process.env.DB_HOST     || 'localhost',
  port:     parseInt(process.env.DB_PORT || '5432', 10),
  database: process.env.DB_NAME     || 'psphere',
  user:     process.env.DB_USER     || 'postgres',
  password: process.env.DB_PASSWORD || '',
  max: 5,
});

pool.on('error', (err) => {
  console.error('[db] Unexpected pool error:', err);
});
