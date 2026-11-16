package com.psphere.desktop.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the words in a photo (receipts, signs, screenshots, documents) with PaddleOCR's models
 * (Apache 2.0): one finds the lines of text, the other reads each line.
 */
class Ocr(private val dir: File, private val threads: Int = 1) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private var findSession: OrtSession? = null
    private var readSession: OrtSession? = null
    private val alphabet: List<String> by lazy { File(dir, SmartModels.OCR_DICT).readLines(Charsets.UTF_8).filter { it.isNotEmpty() } }

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads); setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val finder: OrtSession
        @Synchronized get() = findSession ?: env.createSession(File(dir, SmartModels.OCR_FIND).path, options()).also { findSession = it }
    private val reader: OrtSession
        @Synchronized get() = readSession ?: env.createSession(File(dir, SmartModels.OCR_READ).path, options()).also { readSession = it }

    /** The text in [img], line by line from the top, or "" if there is none. */
    fun read(img: BufferedImage): String {
        val lines = findLines(img)
        if (lines.isEmpty()) return ""
        val out = ArrayList<String>()
        for (box in lines) {
            val text = readLine(img, box) ?: continue
            out += text
        }
        return out.joinToString("\n")
    }

    // ── Finding lines (DB detector) ──────────────────────────────────────────

    private class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun findLines(img: BufferedImage): List<Box> {
        val scale = min(1.0, FIND_MAX_SIDE.toDouble() / max(img.width, img.height))
        val w = max(32, ((img.width * scale) / 32).roundToInt() * 32)
        val h = max(32, ((img.height * scale) / 32).roundToInt() * 32)
        val small = resized(img, w, h)
        val plane = w * h
        val input = FloatArray(3 * plane)
        val px = small.getRGB(0, 0, w, h, null, 0, w)
        for (i in 0 until plane) { // BGR, ImageNet mean/std, the way PaddleOCR feeds it
            val p = px[i]
            input[i] = ((p and 0xFF) / 255f - 0.485f) / 0.229f
            input[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            input[2 * plane + i] = (((p shr 16) and 0xFF) / 255f - 0.406f) / 0.225f
        }
        val prob: Array<FloatArray>
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            finder.run(mapOf(finder.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                prob = (out.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        // Group text pixels into regions; each region is one line or word.
        val seen = Array(h) { BooleanArray(w) }
        val boxes = ArrayList<Box>()
        val stack = IntArray(plane)
        for (y0 in 0 until h) for (x0 in 0 until w) {
            if (seen[y0][x0] || prob[y0][x0] < PIXEL_THRESHOLD) continue
            var top = 0; stack[top++] = y0 * w + x0; seen[y0][x0] = true
            var minX = x0; var maxX = x0; var minY = y0; var maxY = y0; var n = 0; var sum = 0.0
            while (top > 0) {
                val v = stack[--top]; val x = v % w; val y = v / w
                n++; sum += prob[y][x]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && !seen[y][x - 1] && prob[y][x - 1] >= PIXEL_THRESHOLD) { seen[y][x - 1] = true; stack[top++] = v - 1 }
                if (x < w - 1 && !seen[y][x + 1] && prob[y][x + 1] >= PIXEL_THRESHOLD) { seen[y][x + 1] = true; stack[top++] = v + 1 }
                if (y > 0 && !seen[y - 1][x] && prob[y - 1][x] >= PIXEL_THRESHOLD) { seen[y - 1][x] = true; stack[top++] = v - w }
                if (y < h - 1 && !seen[y + 1][x] && prob[y + 1][x] >= PIXEL_THRESHOLD) { seen[y + 1][x] = true; stack[top++] = v + w }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (n < MIN_REGION_PIXELS || min(bw, bh) < 3 || sum / n < BOX_THRESHOLD) continue
            // The detector marks a shrunken core of each line; grow it back (DB "unclip").
            val d = (bw * bh * UNCLIP) / (2.0 * (bw + bh))
            val fx = img.width.toDouble() / w; val fy = img.height.toDouble() / h
            val x1 = ((minX - d) * fx).toInt().coerceAtLeast(0)
            val y1 = ((minY - d) * fy).toInt().coerceAtLeast(0)
            val x2 = ((maxX + 1 + d) * fx).toInt().coerceAtMost(img.width)
            val y2 = ((maxY + 1 + d) * fy).toInt().coerceAtMost(img.height)
            if (x2 - x1 >= 4 && y2 - y1 >= 4) boxes += Box(x1, y1, x2 - x1, y2 - y1)
        }
        // Reading order: top to bottom, then left to right within a line.
        return boxes.sortedWith(compareBy<Box>({ (it.y + it.h / 2) / max(1, it.h) }, { it.x })).take(MAX_LINES)
    }

    // ── Reading a line (CTC recogniser) ──────────────────────────────────────

    private fun readLine(img: BufferedImage, b: Box): String? {
        if (b.h > b.w * 1.5) return null // vertical text isn't supported
        val w = min(MAX_LINE_WIDTH, ceil(READ_HEIGHT * b.w.toDouble() / b.h).toInt()).coerceAtLeast(READ_HEIGHT / 2)
        val line = BufferedImage(w, READ_HEIGHT, BufferedImage.TYPE_INT_RGB)
        line.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, READ_HEIGHT, b.x, b.y, b.x + b.w, b.y + b.h, null); dispose()
        }
        val plane = w * READ_HEIGHT
        val input = FloatArray(3 * plane)
        val px = line.getRGB(0, 0, w, READ_HEIGHT, null, 0, w)
        for (i in 0 until plane) {
            val p = px[i]
            input[i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
            input[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * plane + i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
        }
        val steps: Array<FloatArray>
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, READ_HEIGHT.toLong(), w.toLong())).use { t ->
            reader.run(mapOf(reader.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                steps = (out.get(0).value as Array<Array<FloatArray>>)[0]
            }
        }
        // Greedy CTC: best class per step, merge repeats, drop blanks (class 0).
        val sb = StringBuilder()
        var last = -1; var conf = 0.0; var n = 0
        for (s in steps) {
            var best = 0
            for (k in 1 until s.size) if (s[k] > s[best]) best = k
            if (best != 0 && best != last) {
                sb.append(if (best - 1 < alphabet.size) alphabet[best - 1] else " ")
                conf += s[best]; n++
            }
            last = best
        }
        val text = sb.toString().trim()
        return if (text.isEmpty() || n == 0 || conf / n < MIN_CONFIDENCE) null else text
    }

    private fun resized(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null); dispose()
        }
        return out
    }

    @Synchronized
    override fun close() {
        runCatching { findSession?.close() }; findSession = null
        runCatching { readSession?.close() }; readSession = null
    }

    companion object {
        private const val FIND_MAX_SIDE = 960
        private const val PIXEL_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6
        private const val UNCLIP = 1.6
        private const val MIN_REGION_PIXELS = 12
        private const val MAX_LINES = 60
        private const val READ_HEIGHT = 48
        private const val MAX_LINE_WIDTH = 1600
        private const val MIN_CONFIDENCE = 0.6
    }
}
