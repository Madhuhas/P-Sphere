import { config } from './config/env';
import app from './app';
import { pool } from './db/pool';
import { runMigrations } from './db/migrate';
import { startExifWorker } from './queue/exifWorker';

async function start() {
  await pool.connect().then((c) => { c.release(); console.log('[db] Postgres connected.'); });
  await runMigrations();

  const exifWorker = startExifWorker();
  console.log('[exif-worker] Started — listening on photo.extract_exif queue');

  const server = app.listen(config.port, () => {
    console.log(`P-Sphere core running on port ${config.port} [${config.nodeEnv}]`);
  });

  let shuttingDown = false;

  async function shutdown(signal: string) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[shutdown] Received ${signal} — shutting down gracefully...`);

    // Force-exit if graceful shutdown takes too long
    const forceExit = setTimeout(() => {
      console.error('[shutdown] Timed out — forcing exit');
      process.exit(1);
    }, 10_000);
    forceExit.unref();

    await new Promise<void>((resolve) => server.close(() => resolve()));
    await exifWorker.close();
    await pool.end();

    clearTimeout(forceExit);
    console.log('[shutdown] Clean exit');
    process.exit(0);
  }

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT',  () => shutdown('SIGINT'));
}

start().catch((err) => {
  console.error('[startup] Failed to start server:', err);
  process.exit(1);
});
