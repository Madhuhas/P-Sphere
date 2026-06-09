import { Worker, Job } from 'bullmq';
import { config } from '../config/env';
import { batchIngestPhotos } from '../services/photo.service';
import { QUEUE_PHOTO_EXIF, ExifBatchPayload } from './producer';

const connection = { host: config.redis.host, port: config.redis.port };

async function processExifBatch(job: Job<ExifBatchPayload>): Promise<void> {
  const { items } = job.data;
  console.log(`[exif-worker] Processing batch of ${items.length} photo(s)`);

  await job.updateProgress(0);
  const { succeeded, failed } = await batchIngestPhotos(items, 4);
  await job.updateProgress(100);

  console.log(`[exif-worker] Batch complete — ${succeeded} succeeded, ${failed} failed`);
  if (failed > 0) {
    throw new Error(`${failed} of ${items.length} EXIF extractions failed`);
  }
}

export function startExifWorker(): Worker<ExifBatchPayload> {
  const worker = new Worker<ExifBatchPayload>(
    QUEUE_PHOTO_EXIF,
    processExifBatch,
    { connection, concurrency: 2 }
  );

  worker.on('completed', (job) => {
    console.log(`[exif-worker] Job ${job.id} completed`);
  });

  worker.on('failed', (job, err) => {
    console.error(`[exif-worker] Job ${job?.id} failed:`, err.message);
  });

  worker.on('error', (err) => {
    console.error('[exif-worker] Worker error:', err);
  });

  return worker;
}
