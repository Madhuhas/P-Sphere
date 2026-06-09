import path from 'path';
import multer from 'multer';
import fs from 'fs';

const DATA_ROOT = process.env.DATA_ROOT || path.join(process.cwd(), 'data');

export function filesDir(userId: string, deviceId: string): string {
  const dir = path.join(DATA_ROOT, 'files', userId, deviceId);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export function thumbsDir(): string {
  const dir = path.join(DATA_ROOT, 'thumbs');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => {
      const tmp = path.join(DATA_ROOT, 'tmp');
      fs.mkdirSync(tmp, { recursive: true });
      cb(null, tmp);
    },
    filename: (_req, _file, cb) => {
      cb(null, `${Date.now()}-${Math.random().toString(36).slice(2)}`);
    },
  }),
  limits: { fileSize: 500 * 1024 * 1024 },
});
