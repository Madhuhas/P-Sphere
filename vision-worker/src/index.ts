import dotenv from 'dotenv';
dotenv.config();

import { startWorker } from './queue/worker';
import { QUEUE_PHOTO_PROCESS, redisConnection } from './queue/queues';

console.log(`[vision-worker] Starting...`);
console.log(`[vision-worker] Redis: ${redisConnection.host}:${redisConnection.port}`);
console.log(`[vision-worker] Queue: ${QUEUE_PHOTO_PROCESS}`);

const worker = startWorker();

async function shutdown() {
  console.log('[vision-worker] Shutting down gracefully...');
  await worker.close();
  process.exit(0);
}

process.on('SIGTERM', shutdown);
process.on('SIGINT',  shutdown);

console.log('[vision-worker] Ready — waiting for jobs.');
