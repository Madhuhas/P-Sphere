import dotenv from 'dotenv';
dotenv.config();

export const QUEUE_PHOTO_PROCESS = 'photo.process';
export const QUEUE_PHOTO_EXIF    = 'photo.extract_exif';

export const redisConnection = {
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT || '6379', 10),
};

export interface PhotoProcessPayload {
  photoId:  string;
  fileId:   string;
  filePath: string;
  userId:   string;
}
