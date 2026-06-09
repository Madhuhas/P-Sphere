import sharp from 'sharp';

export const MODEL_INPUT_SIZE = 640;

export interface PreprocessedImage {
  tensor: Float32Array;
  scaleX: number;
  scaleY: number;
  originalWidth: number;
  originalHeight: number;
}

/**
 * Resize image to MODEL_INPUT_SIZE x MODEL_INPUT_SIZE, return CHW float32 tensor
 * normalised to [0, 1], plus scale factors to map boxes back to original coords.
 */
export async function preprocessImage(imagePath: string): Promise<PreprocessedImage> {
  const { data, info } = await sharp(imagePath)
    .rotate()
    .resize(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, { fit: 'fill' })
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const { width, height, channels } = info;
  const pixels = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE;
  const tensor = new Float32Array(channels * pixels);

  // HWC → CHW and normalise to [0,1]
  for (let i = 0; i < pixels; i++) {
    const base = i * channels;
    tensor[i]               = (data[base]!     ) / 255;   // R channel
    tensor[pixels + i]      = (data[base + 1]! ) / 255;   // G channel
    tensor[pixels * 2 + i]  = (data[base + 2]! ) / 255;   // B channel
  }

  const meta = await sharp(imagePath).metadata();

  return {
    tensor,
    scaleX: (meta.width  ?? width)  / MODEL_INPUT_SIZE,
    scaleY: (meta.height ?? height) / MODEL_INPUT_SIZE,
    originalWidth:  meta.width  ?? width,
    originalHeight: meta.height ?? height,
  };
}
