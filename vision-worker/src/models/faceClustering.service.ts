import { pool } from '../utils/db';
import {
  Cluster,
  findBestCluster,
  meanVector,
  EMBED_DIM,
} from './FaceClusterer';

interface FaceRow {
  id:               string;
  photo_id:         string;
  embedding_vector: number[] | null;
  cluster_id:       string | null;
}

interface ClusterRow {
  id:      string;
  user_id: string;
}

function parseEmbedding(raw: unknown): number[] | null {
  if (!raw) return null;
  // Postgres REAL[] arrives as a JS array from the pg driver
  if (Array.isArray(raw)) return (raw as number[]).map(Number);
  if (typeof raw === 'string') {
    const cleaned = (raw as string).replace(/[{}]/g, '');
    if (!cleaned) return null;
    return cleaned.split(',').map(Number);
  }
  return null;
}

async function loadClusters(userId: string): Promise<Cluster[]> {
  // Load all faces that belong to clusters of this user, to build centroids
  const { rows } = await pool.query<{
    cluster_id: string;
    embedding_vector: unknown;
  }>(
    `SELECT f.cluster_id, f.embedding_vector
     FROM faces f
     JOIN face_clusters fc ON fc.id = f.cluster_id
     WHERE fc.user_id = $1 AND f.embedding_vector IS NOT NULL AND f.cluster_id IS NOT NULL`,
    [userId]
  );

  const map = new Map<string, number[][]>();
  for (const row of rows) {
    const vec = parseEmbedding(row.embedding_vector);
    if (!vec || vec.length !== EMBED_DIM) continue;
    if (!map.has(row.cluster_id)) map.set(row.cluster_id, []);
    map.get(row.cluster_id)!.push(vec);
  }

  return Array.from(map.entries()).map(([id, members]) => ({
    id,
    centroid: meanVector(members),
    members,
  }));
}

async function createCluster(userId: string): Promise<string> {
  const { rows } = await pool.query<{ id: string }>(
    `INSERT INTO face_clusters (user_id, label) VALUES ($1, 'Unknown') RETURNING id`,
    [userId]
  );
  return rows[0]!.id;
}

async function assignFaceToCluster(faceId: string, clusterId: string): Promise<void> {
  await pool.query('UPDATE faces SET cluster_id = $1 WHERE id = $2', [clusterId, faceId]);
}

export async function runFaceClustering(userId: string): Promise<{ assigned: number; newClusters: number }> {
  // Load all unassigned faces with embeddings for this user
  const { rows: unassigned } = await pool.query<FaceRow>(
    `SELECT f.id, f.photo_id, f.embedding_vector, f.cluster_id
     FROM faces f
     JOIN photos p ON p.id = f.photo_id
     JOIN files  fi ON fi.id = p.file_id
     JOIN devices d ON d.id = fi.device_id
     WHERE d.user_id = $1
       AND f.cluster_id IS NULL
       AND f.embedding_vector IS NOT NULL`,
    [userId]
  );

  if (unassigned.length === 0) return { assigned: 0, newClusters: 0 };

  const clusters = await loadClusters(userId);
  let assigned = 0;
  let newClusters = 0;

  for (const face of unassigned) {
    const vec = parseEmbedding(face.embedding_vector);
    if (!vec || vec.length !== EMBED_DIM) continue;

    const match = findBestCluster(vec, clusters);

    if (match) {
      // Add to existing cluster and update centroid
      match.cluster.members.push(vec);
      match.cluster.centroid = meanVector(match.cluster.members);
      await assignFaceToCluster(face.id, match.cluster.id);
    } else {
      // Create a new cluster for this face
      const clusterId = await createCluster(userId);
      const newCluster: Cluster = { id: clusterId, centroid: vec, members: [vec] };
      clusters.push(newCluster);
      await assignFaceToCluster(face.id, clusterId);
      newClusters++;
    }
    assigned++;
  }

  console.log(
    `[faceClustering] user ${userId}: ${assigned} face(s) assigned, ${newClusters} new cluster(s) created`
  );
  return { assigned, newClusters };
}
