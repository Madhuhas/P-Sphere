package com.psphere.desktop.engine

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.psphere.core.model.Media
import com.psphere.core.node.Platform
import com.psphere.core.storage.FileInfo
import com.psphere.core.storage.FileSystemFolder
import com.psphere.core.storage.Folder
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/** Windows' side of the shared core: folders are plain paths; previews and dates come from the files. */
class WindowsPlatform : Platform {

    override fun openFolder(location: String): Folder? {
        val dir = File(location)
        return if (dir.isDirectory) FileSystemFolder(dir) else null
    }

    override fun makePreview(folder: Folder, relPath: String, media: Media): ByteArray? {
        // Videos and formats Java can't read (HEIC, RAW) get no preview here; the phone that
        // took them usually sends one along with the catalog.
        if (media != Media.PHOTO) return null
        val file = fileOf(folder, relPath) ?: return null
        val image = readScaled(file, PREVIEW_PX) ?: return null
        val upright = rotate(image, orientation(file))
        return jpeg(scaleDown(upright, PREVIEW_PX))
    }

    override fun takenAt(folder: Folder, info: FileInfo, media: Media): Long? {
        if (media != Media.PHOTO) return null
        val file = fileOf(folder, info.relPath) ?: return null
        return runCatching {
            val meta = ImageMetadataReader.readMetadata(file)
            // EXIF dates have no time zone; read them as local time, like the camera wrote them.
            val tz = TimeZone.getDefault()
            meta.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)?.getDateOriginal(tz)?.time
                ?: meta.getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getDate(ExifDirectoryBase.TAG_DATETIME, tz)?.time
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun fileOf(folder: Folder, relPath: String): File? =
        (folder as? FileSystemFolder)?.let { File(it.root, relPath.replace('/', File.separatorChar)) }?.takeIf { it.isFile }

    /** Decodes only every n-th pixel of large photos, so a 50 MP image doesn't fill the memory. */
    private fun readScaled(file: File, maxPx: Int): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val longest = max(reader.getWidth(0), reader.getHeight(0))
                val step = (longest / (maxPx * 2)).coerceAtLeast(1)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    private fun orientation(file: File): Int = runCatching {
        ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
    }.getOrNull() ?: 1

    /** Turns the image upright for EXIF orientations 3, 6 and 8 (the ones cameras write). */
    private fun rotate(src: BufferedImage, orientation: Int): BufferedImage {
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

    private fun scaleDown(src: BufferedImage, maxPx: Int): BufferedImage {
        val longest = max(src.width, src.height)
        val f = if (longest > maxPx) maxPx.toDouble() / longest else 1.0
        val w = (src.width * f).toInt().coerceAtLeast(1)
        val h = (src.height * f).toInt().coerceAtLeast(1)
        // Always redraw into plain RGB: JPEG can't hold transparency (PNG screenshots).
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

    private fun jpeg(image: BufferedImage): ByteArray? = runCatching {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ByteArrayOutputStream().use { bytes ->
            ImageIO.createImageOutputStream(bytes).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.72f
                }
                writer.write(null, IIOImage(image, null, null), param)
                writer.dispose()
            }
            bytes.toByteArray()
        }
    }.getOrNull()

    companion object {
        /** Longest side of a preview, the same as on Android. About 30–60 KB as a JPEG. */
        const val PREVIEW_PX = 480
    }
}
