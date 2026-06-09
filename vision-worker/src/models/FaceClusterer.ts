export const SIMILARITY_THRESHOLD = 0.60;  // cosine similarity to join a cluster
export const EMBED_DIM = 512;

export function cosineSimilarity(a: number[], b: number[]): number {
  let dot = 0, normA = 0, normB = 0;
  for (let i = 0; i < a.length; i++) {
    dot   += a[i]! * b[i]!;
    normA += a[i]! * a[i]!;
    normB += b[i]! * b[i]!;
  }
  const denom = Math.sqrt(normA) * Math.sqrt(normB);
  return denom === 0 ? 0 : dot / denom;
}

export function meanVector(vectors: number[][]): number[] {
  if (vectors.length === 0) return new Array(EMBED_DIM).fill(0) as number[];
  const mean = new Array(EMBED_DIM).fill(0) as number[];
  for (const vec of vectors) {
    for (let i = 0; i < EMBED_DIM; i++) mean[i]! += vec[i]!;
  }
  const n = vectors.length;
  return mean.map((v) => v / n);
}

export interface Cluster {
  id:       string;
  centroid: number[];
  members:  number[][];  // embeddings of all assigned faces
}

/**
 * Assign `embedding` to the nearest cluster whose centroid similarity >= threshold,
 * or return null to signal a new cluster should be created.
 */
export function findBestCluster(
  embedding: number[],
  clusters: Cluster[]
): { cluster: Cluster; similarity: number } | null {
  let best: { cluster: Cluster; similarity: number } | null = null;
  for (const cluster of clusters) {
    const sim = cosineSimilarity(embedding, cluster.centroid);
    if (sim >= SIMILARITY_THRESHOLD && (best === null || sim > best.similarity)) {
      best = { cluster, similarity: sim };
    }
  }
  return best;
}
