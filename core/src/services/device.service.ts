import { pool } from '../db/pool';
import crypto from 'crypto';

export interface DeviceInput {
  userId: string;
  name: string;
  type: string;
}

export async function registerDevice({ userId, name, type }: DeviceInput) {
  const deviceToken = crypto.randomBytes(32).toString('hex');
  const { rows } = await pool.query(
    `INSERT INTO devices (user_id, name, type, device_token)
     VALUES ($1, $2, $3, $4)
     RETURNING id, name, type, device_token, created_at`,
    [userId, name, type, deviceToken]
  );
  const row = rows[0];
  return { deviceId: row.id as string, token: row.device_token as string, name: row.name as string, type: row.type as string, createdAt: row.created_at as string };
}

export async function getUserDevices(userId: string) {
  const { rows } = await pool.query(
    'SELECT id, name, type, sync_enabled, last_seen, created_at FROM devices WHERE user_id = $1 ORDER BY created_at DESC',
    [userId]
  );
  return rows;
}

export async function removeDevice(deviceId: string, userId: string) {
  const { rowCount } = await pool.query(
    'DELETE FROM devices WHERE id = $1 AND user_id = $2',
    [deviceId, userId]
  );
  return (rowCount ?? 0) > 0;
}
