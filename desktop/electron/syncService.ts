import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { BrowserWindow } from 'electron';

export interface FileEntry {
  absolutePath: string;
  relativePath: string;
  hash: string;
  size: number;
  modifiedAt: string;
  mimeType: string;
}

export interface SyncProgress {
  stage: 'scan' | 'check' | 'upload' | 'done' | 'error';
  message: string;
  done?: number;
  total?: number;
}

// ── File hashing ──────────────────────────────────────────────────────────────

function hashFile(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end',  () => resolve(hash.digest('hex')));
    stream.on('error', reject);
  });
}

function guessMime(filePath: string): string {
  const ext = path.extname(filePath).toLowerCase();
  const map: Record<string, string> = {
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
    '.png': 'image/png',  '.gif': 'image/gif',
    '.webp': 'image/webp', '.heic': 'image/heic',
    '.mp4': 'video/mp4',  '.mov': 'video/quicktime',
    '.pdf': 'application/pdf',
  };
  return map[ext] ?? 'application/octet-stream';
}

function scanDirectory(rootDir: string): string[] {
  const files: string[] = [];
  function walk(dir: string) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) { walk(full); }
      else if (entry.isFile())  { files.push(full); }
    }
  }
  walk(rootDir);
  return files;
}

// ── API helpers (Node fetch) ───────────────────────────────────────────────────

async function apiCheck(
  baseUrl: string, jwt: string, deviceToken: string, hashes: string[]
): Promise<string[]> {
  const res = await fetch(`${baseUrl}/api/sync/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${jwt}` },
    body: JSON.stringify({ deviceToken, hashes }),
  });
  if (!res.ok) throw new Error(`/sync/check ${res.status}`);
  const data = await res.json() as { missing: string[] };
  return data.missing;
}

async function apiUpload(
  baseUrl: string, jwt: string, deviceToken: string, entry: FileEntry
): Promise<void> {
  const boundary = `----PSphere${Date.now()}`;
  const fileBuffer = fs.readFileSync(entry.absolutePath);

  const parts: Buffer[] = [];
  const field = (name: string, value: string) =>
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`
    );

  parts.push(field('deviceToken', deviceToken));
  parts.push(field('path',        entry.relativePath));
  parts.push(field('hash',        entry.hash));
  parts.push(field('modifiedAt',  entry.modifiedAt));
  parts.push(
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${path.basename(entry.absolutePath)}"\r\nContent-Type: ${entry.mimeType}\r\n\r\n`
    )
  );
  parts.push(fileBuffer);
  parts.push(Buffer.from(`\r\n--${boundary}--\r\n`));

  const body = Buffer.concat(parts);

  const res = await fetch(`${baseUrl}/api/sync/upload`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${jwt}`,
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
    },
    body,
  });

  if (!res.ok) throw new Error(`/sync/upload ${res.status} for ${entry.relativePath}`);
}

// ── Main orchestrator ─────────────────────────────────────────────────────────

export async function syncFolder(
  rootDir: string,
  baseUrl: string,
  jwt: string,
  deviceToken: string,
  win: BrowserWindow
): Promise<void> {
  const send = (progress: SyncProgress) => {
    if (!win.isDestroyed()) win.webContents.send('sync-progress', progress);
  };

  try {
    // 1 — Scan
    send({ stage: 'scan', message: `Scanning ${rootDir}…` });
    const filePaths = scanDirectory(rootDir);
    send({ stage: 'scan', message: `Found ${filePaths.length} file(s). Hashing…` });

    const entries: FileEntry[] = [];
    for (const fp of filePaths) {
      const stat = fs.statSync(fp);
      entries.push({
        absolutePath: fp,
        relativePath: path.relative(rootDir, fp).replace(/\\/g, '/'),
        hash:         await hashFile(fp),
        size:         stat.size,
        modifiedAt:   new Date(stat.mtimeMs).toISOString(),
        mimeType:     guessMime(fp),
      });
    }

    // 2 — Check
    send({ stage: 'check', message: 'Checking which files the server needs…' });
    const hashes  = entries.map((e) => e.hash);
    const missing = new Set(await apiCheck(baseUrl, jwt, deviceToken, hashes));
    const toUpload = entries.filter((e) => missing.has(e.hash));

    send({ stage: 'check', message: `${toUpload.length} new / ${entries.length - toUpload.length} already synced.` });

    if (toUpload.length === 0) {
      send({ stage: 'done', message: 'Everything is up to date.' });
      return;
    }

    // 3 — Upload
    let uploaded = 0;
    let failed   = 0;
    for (const entry of toUpload) {
      try {
        await apiUpload(baseUrl, jwt, deviceToken, entry);
        uploaded++;
      } catch (err) {
        failed++;
        send({ stage: 'upload', message: `✗ ${entry.relativePath}: ${err instanceof Error ? err.message : ''}`, done: uploaded, total: toUpload.length });
        continue;
      }
      send({ stage: 'upload', message: `↑ ${entry.relativePath}`, done: uploaded, total: toUpload.length });
    }

    send({ stage: 'done', message: `Done. ↑${uploaded} uploaded, ✗${failed} failed, ⏭${entries.length - toUpload.length} skipped.` });
  } catch (err) {
    send({ stage: 'error', message: err instanceof Error ? err.message : String(err) });
  }
}
