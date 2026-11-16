package com.psphere.desktop.engine

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream
import kotlin.math.max

/** Reading photos the right way up and at a sensible size, for previews and smart features. */
object Images {

    /** The photo in [file], upright, with its longest side at most [maxPx]. Null if Java can't read it (HEIC, RAW). */
    fun readUpright(file: File, maxPx: Int): BufferedImage? {
        val image = runCatching { ImageIO.createImageInputStream(file).use { readScaled(it, maxPx) } }.getOrNull() ?: return null
        return scaleDown(rotate(image, orientation { ImageMetadataReader.readMetadata(file) }), maxPx)
    }

    /** Same as above for a photo held in memory. */
    fun readUpright(bytes: ByteArray, maxPx: Int): BufferedImage? {
        val image = runCatching { ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { readScaled(it, maxPx) } }.getOrNull() ?: return null
        return scaleDown(rotate(image, orientation { ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes)) }), maxPx)
    }

    /** Decodes only every n-th pixel of large photos, so a 50 MP image doesn't fill the memory. */
    private fun readScaled(input: ImageInputStream?, maxPx: Int): BufferedImage? {
        input ?: return null
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            val longest = max(reader.getWidth(0), reader.getHeight(0))
            val step = (longest / (maxPx * 2)).coerceAtLeast(1)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
            return reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }

    private fun orientation(meta: () -> com.drew.metadata.Metadata): Int = runCatching {
        meta().getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
    }.getOrNull() ?: 1

    /** Turns the image upright for EXIF orientations 3, 6 and 8 (the ones cameras write). */
    fun rotate(src: BufferedImage, orientation: Int): BufferedImage {
        val degrees = when (orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> return src }
        val swap = degrees != 180
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val t = AffineTransform().apply {
            translate(w / 2.0, h / 2.0)
            rotate(Math.toRadians(degrees.toDouble()))
            translate(-src.width / 2.0, -src.height / 2.0)
        }
        out.createGraphics().apply { drawImage(src, t, null); dispose() }
        return out
    }

    /** Plain RGB copy no larger than [maxPx] on its longest side (JPEG can't hold transparency). */
    fun scaleDown(src: BufferedImage, maxPx: Int): BufferedImage {
        val longest = max(src.width, src.height)
        val f = if (longest > maxPx) maxPx.toDouble() / longest else 1.0
        if (f == 1.0 && src.type == BufferedImage.TYPE_INT_RGB) return src
        val w = (src.width * f).toInt().coerceAtLeast(1)
        val h = (src.height * f).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = java.awt.Color.WHITE
            fillRect(0, 0, w, h)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return out
    }

    fun jpeg(image: BufferedImage, quality: Float = 0.72f): ByteArray? = runCatching {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ByteArrayOutputStream().use { bytes ->
            ImageIO.createImageOutputStream(bytes).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), param)
                writer.dispose()
            }
            bytes.toByteArray()
        }
    }.getOrNull()
}
