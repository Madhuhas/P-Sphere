package com.psphere.desktop.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A face in a photo: where it is (0..1 of the image's width and height) and its fingerprint. */
class FoundFace(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val embedding: FloatArray)

/**
 * Finds faces and tells them apart, using OpenCV's YuNet (finding, MIT licence) and SFace
 * (recognising, Apache 2.0). Two fingerprints of the same person point in about the same
 * direction; see [SAME_PERSON].
 */
class Faces(private val dir: File, private val threads: Int = 1) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private var detectorSession: OrtSession? = null
    private var recognizerSession: OrtSession? = null

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads); setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detector: OrtSession
        @Synchronized get() = detectorSession ?: env.createSession(File(dir, SmartModels.FACE_FIND).path, options()).also { detectorSession = it }
    private val recognizer: OrtSession
        @Synchronized get() = recognizerSession ?: env.createSession(File(dir, SmartModels.FACE_KNOW).path, options()).also { recognizerSession = it }

    /** Every clear face in [img], largest first. */
    fun find(img: BufferedImage): List<FoundFace> {
        val dets = detect(img)
            .filter { min(it.w, it.h) >= MIN_FACE_PX && min(it.w, it.h) >= MIN_FACE_FRACTION * max(img.width, img.height) }
            .sortedByDescending { it.w * it.h }
            .take(MAX_FACES)
        return dets.map { d ->
            FoundFace(d.x / img.width, d.y / img.height, d.w / img.width, d.h / img.height, d.score, embed(img, d.landmarks))
        }
    }

    // ── Finding (YuNet) ──────────────────────────────────────────────────────

    private class Det(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val landmarks: FloatArray)

    private fun detect(img: BufferedImage): List<Det> {
        // Fit into 640×640 without stretching; the rest stays black.
        val scale = DET_SIZE.toFloat() / max(img.width, img.height)
        val sw = (img.width * scale).toInt().coerceAtLeast(1)
        val sh = (img.height * scale).toInt().coerceAtLeast(1)
        val canvas = BufferedImage(DET_SIZE, DET_SIZE, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, sw, sh, null); dispose()
        }
        // YuNet reads BGR pixel values as they are (0..255).
        val plane = DET_SIZE * DET_SIZE
        val input = FloatArray(3 * plane)
        val px = canvas.getRGB(0, 0, DET_SIZE, DET_SIZE, null, 0, DET_SIZE)
        for (i in 0 until plane) {
            val p = px[i]
            input[i] = (p and 0xFF).toFloat()
            input[plane + i] = ((p shr 8) and 0xFF).toFloat()
            input[2 * plane + i] = ((p shr 16) and 0xFF).toFloat()
        }
        val found = ArrayList<Det>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong())).use { t ->
            detector.run(mapOf(detector.inputNames.first() to t)).use { out ->
                for (stride in intArrayOf(8, 16, 32)) {
                    val cls = rows(out, "cls_$stride"); val obj = rows(out, "obj_$stride")
                    val bbox = rows(out, "bbox_$stride"); val kps = rows(out, "kps_$stride")
                    val cols = DET_SIZE / stride
                    for (i in cls.indices) {
                        val score = sqrt(cls[i][0].coerceIn(0f, 1f) * obj[i][0].coerceIn(0f, 1f))
                        if (score < MIN_SCORE) continue
                        val r = i / cols; val c = i % cols
                        val b = bbox[i]
                        val cx = (c + b[0]) * stride; val cy = (r + b[1]) * stride
                        val w = exp(b[2]) * stride; val h = exp(b[3]) * stride
                        val k = kps[i]
                        val lm = FloatArray(10) { j -> ((if (j % 2 == 0) c else r) + k[j]) * stride / scale }
                        found += Det((cx - w / 2) / scale, (cy - h / 2) / scale, w / scale, h / scale, score, lm)
                    }
                }
            }
        }
        return nms(found)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(out: OrtSession.Result, name: String): Array<FloatArray> = (out.get(name).get().value as Array<Array<FloatArray>>)[0]

    private fun nms(dets: List<Det>): List<Det> {
        val kept = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (kept.none { iou(it, d) > NMS_IOU }) kept += d
        }
        return kept
    }

    private fun iou(a: Det, b: Det): Float {
        val ix = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = ix * iy
        return inter / (a.w * a.h + b.w * b.h - inter)
    }

    // ── Recognising (SFace) ──────────────────────────────────────────────────

    /** Lines the face up with the standard 112×112 face (eyes, nose, mouth in fixed places), then fingerprints it. */
    private fun embed(img: BufferedImage, lm: FloatArray): FloatArray {
        val m = similarity(lm, TEMPLATE)
        val face = BufferedImage(FACE_SIZE, FACE_SIZE, BufferedImage.TYPE_INT_RGB)
        face.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, m, null); dispose()
        }
        val plane = FACE_SIZE * FACE_SIZE
        val input = FloatArray(3 * plane)
        val px = face.getRGB(0, 0, FACE_SIZE, FACE_SIZE, null, 0, FACE_SIZE)
        for (i in 0 until plane) { // SFace reads RGB values as they are (0..255)
            val p = px[i]
            input[i] = ((p shr 16) and 0xFF).toFloat()
            input[plane + i] = ((p shr 8) and 0xFF).toFloat()
            input[2 * plane + i] = (p and 0xFF).toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, FACE_SIZE.toLong(), FACE_SIZE.toLong())).use { t ->
            recognizer.run(mapOf(recognizer.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return Clip.normalize((out.get(0).value as Array<FloatArray>)[0])
            }
        }
    }

    /** Best rotation + uniform scale + shift taking the five points [src] onto [dst] (least squares, no mirroring). */
    private fun similarity(src: FloatArray, dst: FloatArray): AffineTransform {
        val n = src.size / 2
        var msx = 0.0; var msy = 0.0; var mdx = 0.0; var mdy = 0.0
        for (i in 0 until n) { msx += src[2 * i]; msy += src[2 * i + 1]; mdx += dst[2 * i]; mdy += dst[2 * i + 1] }
        msx /= n; msy /= n; mdx /= n; mdy /= n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val sx = src[2 * i] - msx; val sy = src[2 * i + 1] - msy
            val dx = dst[2 * i] - mdx; val dy = dst[2 * i + 1] - mdy
            num1 += sx * dx + sy * dy; num2 += sx * dy - sy * dx; den += sx * sx + sy * sy
        }
        val a = num1 / den; val b = num2 / den
        val tx = mdx - (a * msx - b * msy); val ty = mdy - (b * msx + a * msy)
        return AffineTransform(a, b, -b, a, tx, ty)
    }

    @Synchronized
    override fun close() {
        runCatching { detectorSession?.close() }; detectorSession = null
        runCatching { recognizerSession?.close() }; recognizerSession = null
    }

    companion object {
        /** Fingerprints at least this similar are taken to be the same person (OpenCV's SFace threshold). */
        const val SAME_PERSON = 0.363f
        private const val DET_SIZE = 640
        private const val FACE_SIZE = 112
        private const val MIN_SCORE = 0.85f
        private const val NMS_IOU = 0.3f
        /** Smaller faces are too blurry to recognise reliably. */
        private const val MIN_FACE_PX = 36
        private const val MIN_FACE_FRACTION = 0.04f
        private const val MAX_FACES = 12
        /** Where eyes, nose tip and mouth corners sit in the standard 112×112 face. */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
        )
    }
}
