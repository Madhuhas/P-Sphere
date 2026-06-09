import path from 'path';
import fs from 'fs';
import sharp from 'sharp';
import * as ort from 'onnxruntime-node';

// Standard ArcFace / MobileFaceNet input
const EMBED_SIZE = 112;
const EMBED_DIM  = 512;
const MODELS_DIR = process.env.MODELS_DIR ?? path.join(process.cwd(), 'models');
// Expected model: mobilefacenet.onnx  (~4 MB)
// Download from: https://github.com/onnx/models  (face recognition models)
const MODEL_PATH = path.join(MODELS_DIR, 'mobilefacenet.onnx');

let session: ort.InferenceSession | null = null;

async function getSession(): Promise<ort.InferenceSession> {
  if (!session) {
    session = await ort.InferenceSession.create(MODEL_PATH, {
      executionProviders: ['cpu'],
    });
  }
  return session;
}

function l2normalize(vec: Float32Array): Float32Array {
  let norm = 0;
  for (const v of vec) norm += v * v;
  norm = Math.sqrt(norm);
  if (norm === 0) return vec;
  return Float32Array.from(vec, (v) => v / norm);
}

export interface BBoxInput {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Crop face region, resize to 112×112, normalize to [-1, 1], run ONNX inference.
 * Returns a L2-normalized 512-dim embedding, or null if model is unavailable.
 */
export async function embedFace(
  imagePath: string,
  bbox: BBoxInput
): Promise<Float32Array | null> {
  if (!fs.existsSync(MODEL_PATH)) {
    console.warn(`[FaceEmbedder] Model not found at ${MODEL_PATH} — skipping embeddings.`);
    return null;
  }

  const { data } = await sharp(imagePath)
    .rotate()
    .extract({
      left:   Math.max(0, Math.round(bbox.x)),
      top:    Math.max(0, Math.round(bbox.y)),
      width:  Math.max(1, Math.round(bbox.w)),
      height: Math.max(1, Math.round(bbox.h)),
    })
    .resize(EMBED_SIZE, EMBED_SIZE, { fit: 'fill' })
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
    .then((r) => r);

  const pixels = EMBED_SIZE * EMBED_SIZE;
  const tensor = new Float32Array(3 * pixels);

  // HWC → CHW, normalise to [-1, 1]:  (pixel - 127.5) / 128.0
  for (let i = 0; i < pixels; i++) {
    const base = i * 3;
    tensor[i]              = (data[base]!     - 127.5) / 128.0;
    tensor[pixels + i]     = (data[base + 1]! - 127.5) / 128.0;
    tensor[pixels * 2 + i] = (data[base + 2]! - 127.5) / 128.0;
  }

  const sess = await getSession();
  const inputName = sess.inputNames[0]!;
  const input = new ort.Tensor('float32', tensor, [1, 3, EMBED_SIZE, EMBED_SIZE]);
  const output = await sess.run({ [inputName]: input });
  const raw = output[sess.outputNames[0]!]!.data as Float32Array;

  // Some models output [1, 512], others [512] — flatten either way
  const vec = raw.length === EMBED_DIM ? raw : raw.slice(0, EMBED_DIM);
  return l2normalize(vec);
}
