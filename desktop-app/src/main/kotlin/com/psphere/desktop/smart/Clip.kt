package com.psphere.desktop.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The CLIP model (OpenAI ViT-B/32, MIT licence): turns photos and search text into vectors that
 * point the same way when they mean the same thing. Runs on the CPU with [threads] threads.
 */
class Clip(private val dir: File, private val threads: Int = 1) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    // Each half loads on first use: a search needs only the text half, scanning only the photo half.
    private var visionSession: OrtSession? = null
    private var textSession: OrtSession? = null
    private val vision: OrtSession
        @Synchronized get() = visionSession ?: env.createSession(File(dir, SmartModels.VISION).path, options()).also { visionSession = it }
    private val text: OrtSession
        @Synchronized get() = textSession ?: env.createSession(File(dir, SmartModels.TEXT).path, options()).also { textSession = it }
    private val tokenizer by lazy { ClipTokenizer.load(dir) }

    /** Unit-length vector for a photo (a JPEG preview), or null if it can't be decoded. */
    fun embedImage(jpeg: ByteArray): FloatArray? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return null
        val pixels = preprocess(img)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            vision.run(mapOf(vision.inputNames.first() to t)).use { out ->
                return normalize(firstVector(out, "image_embeds"))
            }
        }
    }

    /** Unit-length vector for a search. */
    @Synchronized
    fun embedText(query: String): FloatArray {
        val ids = tokenizer.encode(query)
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (name in text.inputNames) {
                inputs[name] = when (name) {
                    "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
                    else -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                }
            }
            text.run(inputs).use { out -> return normalize(firstVector(out, "text_embeds")) }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun firstVector(out: OrtSession.Result, preferred: String): FloatArray {
        val value = out.get(preferred).orElse(null) ?: out.get(0)
        @Suppress("UNCHECKED_CAST")
        return (value.value as Array<FloatArray>)[0]
    }

    /** Frees the photo half after a scan, keeping the text half for searches. */
    @Synchronized
    fun closeVision() { runCatching { visionSession?.close() }; visionSession = null }

    @Synchronized
    override fun close() {
        closeVision()
        runCatching { textSession?.close() }
        textSession = null
    }

    companion object {
        const val SIZE = 224
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        /** Shortest side to 224, centre crop to 224×224, then CLIP's normalisation, as NCHW floats. */
        internal fun preprocess(src: BufferedImage): FloatArray {
            val scale = SIZE.toDouble() / minOf(src.width, src.height)
            val w = max(SIZE, (src.width * scale).roundToInt())
            val h = max(SIZE, (src.height * scale).roundToInt())
            val square = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
            square.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src, -(w - SIZE) / 2, -(h - SIZE) / 2, w, h, null)
                dispose()
            }
            val out = FloatArray(3 * SIZE * SIZE)
            val plane = SIZE * SIZE
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val rgb = square.getRGB(x, y)
                val i = y * SIZE + x
                out[i] = (((rgb shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
                out[plane + i] = (((rgb shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                out[2 * plane + i] = ((rgb and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
            return out
        }

        internal fun normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x * x
            val n = sqrt(s).toFloat().takeIf { it > 0 } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }
    }
}
