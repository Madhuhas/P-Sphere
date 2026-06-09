import { Queue } from 'bullmq';
import { config } from '../config/env';

export const QUEUE_PHOTO_PROCESS = 'photo.process';
export const QUEUE_PHOTO_EXIF    = 'photo.extract_exif';

export interface PhotoProcessPayload {
  photoId: string;
  fileId:  string;
  filePath: string;
  userId:  string;
}

export interface ExifBatchItem {
  fileId:   string;
  filePath: string;
  userId:   string;
}

export interface ExifBatchPayload {
  items: ExifBatchItem[];
}

const connection = { host: config.redis.host, port: config.redis.port };

const photoQueue = new Queue<PhotoProcessPayload>(QUEUE_PHOTO_PROCESS, { connection });
const exifQueue  = new Queue<ExifBatchPayload>(QUEUE_PHOTO_EXIF, { connection });

export async function enqueuePhotoProcess(payload: PhotoProcessPayload): Promise<void> {
  await photoQueue.add('process', payload, {
    attempts: 3,
    backoff: { type: 'exponential', delay: 5000 },
    removeOnComplete: 100,
    removeOnFail: 50,
  });
}

export async function enqueueExifBatch(items: ExifBatchItem[]): Promise<void> {
  if (items.length === 0) return;
  await exifQueue.add('exif-batch', { items }, {
    attempts: 3,
    backoff: { type: 'exponential', delay: 5000 },
    removeOnComplete: 100,
    removeOnFail: 50,
  });
}
