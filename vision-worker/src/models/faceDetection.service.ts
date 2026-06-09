import { pool } from '../utils/db';
import { detectFaces, BoundingBox } from './FaceDetector';

export async function runFaceDetection(photoId: string, filePath: string): Promise<number> {
  const boxes = await detectFaces(filePath);
  if (boxes.length === 0) return 0;

  // Delete stale face rows for this photo before inserting fresh ones
  await pool.query('DELETE FROM faces WHERE photo_id = $1', [photoId]);

  for (const box of boxes) {
    await pool.query(
      `INSERT INTO faces (photo_id, bbox, confidence)
       VALUES ($1, $2, $3)`,
      [photoId, JSON.stringify(box satisfies BoundingBox), box.confidence]
    );
  }

  console.log(`[faceDetection] photo ${photoId}: ${boxes.length} face(s) detected`);
  return boxes.length;
}
