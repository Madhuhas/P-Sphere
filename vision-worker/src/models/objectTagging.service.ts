import { pool } from '../utils/db';
import { tagImage } from './ObjectTagger';

export async function runObjectTagging(photoId: string, filePath: string): Promise<number> {
  const predictions = await tagImage(filePath);
  if (predictions.length === 0) return 0;

  // Upsert tags — on conflict update score in case photo is re-processed
  for (const { label, score } of predictions) {
    await pool.query(
      `INSERT INTO photo_tags (photo_id, tag, type, score)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (photo_id, tag)
       DO UPDATE SET score = EXCLUDED.score`,
      [photoId, label.name, label.type, score]
    );
  }

  console.log(
    `[objectTagging] photo ${photoId}: [${predictions.map((p) => p.label.name).join(', ')}]`
  );
  return predictions.length;
}
