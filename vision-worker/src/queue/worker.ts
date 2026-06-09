import { Worker, Job } from 'bullmq';
import { QUEUE_PHOTO_PROCESS, redisConnection, PhotoProcessPayload } from './queues';
import { runFaceDetection } from '../models/faceDetection.service';
import { runFaceEmbeddings } from '../models/faceEmbedding.service';
import { runFaceClustering } from '../models/faceClustering.service';
import { runObjectTagging } from '../models/objectTagging.service';

async function processPhoto(job: Job<PhotoProcessPayload>): Promise<void> {
  const { photoId, filePath } = job.data;
  console.log(`[worker] Processing photo ${photoId} — ${filePath}`);

  // Step 1: Face detection (5.2 — DONE)
  await job.updateProgress(10);
  const faceCount = await runFaceDetection(photoId, filePath);
  console.log(`[worker]   → ${faceCount} face(s) detected`);
  await job.updateProgress(30);

  // Step 2: Face embeddings (5.3 — DONE)
  const embedded = await runFaceEmbeddings(photoId, filePath);
  console.log(`[worker]   → ${embedded} face(s) embedded`);
  await job.updateProgress(50);

  // Step 3: Face clustering (5.4 — DONE)
  const { assigned, newClusters } = await runFaceClustering(job.data.userId);
  console.log(`[worker]   → clustering: ${assigned} assigned, ${newClusters} new clusters`);
  await job.updateProgress(70);

  // Step 4: Object + scene tagging (5.5 — DONE)
  const tagCount = await runObjectTagging(photoId, filePath);
  console.log(`[worker]   → ${tagCount} tag(s) applied`);
  await job.updateProgress(90);

  await job.updateProgress(100);
  console.log(`[worker] ✓ Done — photo ${photoId}`);
}

export function startWorker(): Worker<PhotoProcessPayload> {
  const worker = new Worker<PhotoProcessPayload>(
    QUEUE_PHOTO_PROCESS,
    processPhoto,
    { connection: redisConnection, concurrency: 2 }
  );

  worker.on('completed', (job) => {
    console.log(`[worker] Job ${job.id} completed for photo ${job.data.photoId}`);
  });

  worker.on('failed', (job, err) => {
    console.error(`[worker] Job ${job?.id} failed:`, err.message);
  });

  worker.on('error', (err) => {
    console.error('[worker] Worker error:', err);
  });

  return worker;
}
