import path from 'path';
import fs from 'fs';
import * as ort from 'onnxruntime-node';
import { preprocessImage, MODEL_INPUT_SIZE } from '../utils/imagePreprocessor';

export interface BoundingBox {
  x: number;       // left pixel, original coords
  y: number;       // top pixel, original coords
  w: number;       // width in original coords
  h: number;       // height in original coords
  confidence: number;
}

const CONF_THRESHOLD = 0.45;
const IOU_THRESHOLD  = 0.5;
const MODELS_DIR = process.env.MODELS_DIR ?? path.join(process.cwd(), 'models');
// Expected model: yolov8n-face.onnx  (~6 MB)
// Download from: https://github.com/derronqi/yolov8-face  (ONNX export)
const MODEL_PATH = path.join(MODELS_DIR, 'yolov8n-face.onnx');

let session: ort.InferenceSession | null = null;

async function getSession(): Promise<ort.InferenceSession> {
  if (!session) {
    session = await ort.InferenceSession.create(MODEL_PATH, {
      executionProviders: ['cpu'],
    });
  }
  return session;
}

function iou(a: BoundingBox, b: BoundingBox): number {
  const ax2 = a.x + a.w, ay2 = a.y + a.h;
  const bx2 = b.x + b.w, by2 = b.y + b.h;
  const ix = Math.max(0, Math.min(ax2, bx2) - Math.max(a.x, b.x));
  const iy = Math.max(0, Math.min(ay2, by2) - Math.max(a.y, b.y));
  const inter = ix * iy;
  return inter / (a.w * a.h + b.w * b.h - inter);
}

function nms(boxes: BoundingBox[]): BoundingBox[] {
  boxes.sort((a, b) => b.confidence - a.confidence);
  const kept: BoundingBox[] = [];
  const suppressed = new Set<number>();
  for (let i = 0; i < boxes.length; i++) {
    if (suppressed.has(i)) continue;
    kept.push(boxes[i]!);
    for (let j = i + 1; j < boxes.length; j++) {
      if (iou(boxes[i]!, boxes[j]!) > IOU_THRESHOLD) suppressed.add(j);
    }
  }
  return kept;
}

export async function detectFaces(imagePath: string): Promise<BoundingBox[]> {
  if (!fs.existsSync(MODEL_PATH)) {
    console.warn(`[FaceDetector] Model not found at ${MODEL_PATH} — skipping face detection.`);
    console.warn(`[FaceDetector] Download yolov8n-face.onnx and place it in ${MODELS_DIR}`);
    return [];
  }

  const { tensor, scaleX, scaleY } = await preprocessImage(imagePath);
  const sess = await getSession();

  const inputTensor = new ort.Tensor('float32', tensor, [1, 3, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE]);
  const output = await sess.run({ images: inputTensor });

  // YOLOv8-face output: [1, 5, 8400]  (cx, cy, w, h, conf)
  const raw = output['output0']!.data as Float32Array;
  const numAnchors = 8400;
  const boxes: BoundingBox[] = [];

  for (let i = 0; i < numAnchors; i++) {
    const conf = raw[4 * numAnchors + i]!;
    if (conf < CONF_THRESHOLD) continue;

    const cx = raw[0 * numAnchors + i]!;
    const cy = raw[1 * numAnchors + i]!;
    const w  = raw[2 * numAnchors + i]!;
    const h  = raw[3 * numAnchors + i]!;

    boxes.push({
      x: Math.round((cx - w / 2) * scaleX),
      y: Math.round((cy - h / 2) * scaleY),
      w: Math.round(w * scaleX),
      h: Math.round(h * scaleY),
      confidence: conf,
    });
  }

  return nms(boxes);
}
