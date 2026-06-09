import { pool } from '../utils/db';
import { embedFace, BBoxInput } from './FaceEmbedder';

interface FaceRow {
  id: string;
  bbox: BBoxInput;
}

export async function runFaceEmbeddings(photoId: string, filePath: string): Promise<number> {
  // Fetch faces that still need embeddings
  const { rows } = await pool.query<FaceRow>(
    `SELECT id, bbox FROM faces
     WHERE photo_id = $1 AND embedding_vector IS NULL`,
    [photoId]
  );

  if (rows.length === 0) return 0;

  let embedded = 0;
  for (const face of rows) {
    const vec = await embedFace(filePath, face.bbox);
    if (!vec) continue;   // model absent — skip silently

    // Store as Postgres REAL[] literal  e.g. '{0.1,0.2,...}'
    const pgArray = `{${Array.from(vec).join(',')}}`;
    await pool.query(
      'UPDATE faces SET embedding_vector = $1 WHERE id = $2',
      [pgArray, face.id]
    );
    embedded++;
  }

  if (embedded > 0) {
    console.log(`[faceEmbedding] photo ${photoId}: ${embedded}/${rows.length} face(s) embedded`);
  }
  return embedded;
}
