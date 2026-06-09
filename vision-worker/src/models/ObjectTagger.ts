import path from 'path';
import fs from 'fs';
import sharp from 'sharp';
import * as ort from 'onnxruntime-node';
import { lookupLabel, LabelEntry } from './imagenet_labels';

const INPUT_SIZE  = 224;
const TOP_K       = 5;
const MIN_SCORE   = 0.05;
const MODELS_DIR  = process.env.MODELS_DIR ?? path.join(process.cwd(), 'models');
// Expected: mobilenetv2-7.onnx  (~14 MB)
// Download: https://github.com/onnx/models/tree/main/validated/vision/classification/mobilenet
const MODEL_PATH  = path.join(MODELS_DIR, 'mobilenetv2-7.onnx');

// ImageNet normalisation constants
const MEAN = [0.485, 0.456, 0.406];
const STD  = [0.229, 0.224, 0.225];

let session: ort.InferenceSession | null = null;

async function getSession(): Promise<ort.InferenceSession> {
  if (!session) {
    session = await ort.InferenceSession.create(MODEL_PATH, {
      executionProviders: ['cpu'],
    });
  }
  return session;
}

export interface TagPrediction {
  classId: number;
  label:   LabelEntry;
  score:   number;
}

export async function tagImage(imagePath: string): Promise<TagPrediction[]> {
  if (!fs.existsSync(MODEL_PATH)) {
    console.warn(`[ObjectTagger] Model not found at ${MODEL_PATH} — skipping tagging.`);
    console.warn(`[ObjectTagger] Download mobilenetv2-7.onnx and place it in ${MODELS_DIR}`);
    return [];
  }

  const { data } = await sharp(imagePath)
    .rotate()
    .resize(INPUT_SIZE, INPUT_SIZE, { fit: 'fill' })
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
    .then((r) => r);

  const pixels = INPUT_SIZE * INPUT_SIZE;
  const tensor = new Float32Array(3 * pixels);

  for (let i = 0; i < pixels; i++) {
    const base = i * 3;
    tensor[i]              = ((data[base]!     / 255) - MEAN[0]!) / STD[0]!;
    tensor[pixels + i]     = ((data[base + 1]! / 255) - MEAN[1]!) / STD[1]!;
    tensor[pixels * 2 + i] = ((data[base + 2]! / 255) - MEAN[2]!) / STD[2]!;
  }

  const sess = await getSession();
  const inputName = sess.inputNames[0]!;
  const input = new ort.Tensor('float32', tensor, [1, 3, INPUT_SIZE, INPUT_SIZE]);
  const output = await sess.run({ [inputName]: input });
  const logits = output[sess.outputNames[0]!]!.data as Float32Array;

  // Softmax
  const maxLogit = Math.max(...logits);
  const exps = Array.from(logits, (v) => Math.exp(v - maxLogit));
  const sumExp = exps.reduce((a, b) => a + b, 0);
  const probs = exps.map((v) => v / sumExp);

  // Top-K from our photo-relevant labels only
  const predictions: TagPrediction[] = [];
  for (let i = 0; i < probs.length; i++) {
    const score = probs[i]!;
    if (score < MIN_SCORE) continue;
    const label = lookupLabel(i);
    if (!label) continue;
    predictions.push({ classId: i, label, score });
  }

  return predictions
    .sort((a, b) => b.score - a.score)
    .slice(0, TOP_K);
}
