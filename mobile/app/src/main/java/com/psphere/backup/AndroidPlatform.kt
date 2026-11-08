package com.psphere.backup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.psphere.core.model.Media
import com.psphere.core.node.Platform
import com.psphere.core.storage.FileInfo
import com.psphere.core.storage.Folder
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Android's side of the shared core: folders from the system picker, previews, photo dates. */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext

    override fun openFolder(location: String): Folder? {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return null
        // Only folders the user granted us (and that we still have access to) can be opened.
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (granted) SafFolder(app, uri) else null
    }

    override fun makePreview(folder: Folder, relPath: String, media: Media): ByteArray? {
        val uri = (folder as? SafFolder)?.uriOf(relPath) ?: return null
        val bitmap = when (media) {
            Media.PHOTO -> decodePhoto(uri)
            Media.VIDEO -> videoFrame(uri)
            Media.OTHER -> null
        } ?: return null
        val scaled = scaleDown(bitmap, PREVIEW_PX)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        }
    }

    override fun takenAt(folder: Folder, info: FileInfo, media: Media): Long? {
        if (media != Media.PHOTO) return null
        val uri = (folder as? SafFolder)?.uriOf(info.relPath) ?: return null
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { ExifInterface(it).run { dateTimeOriginal ?: dateTime } }
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun decodePhoto(uri: Uri): Bitmap? {
        // ImageDecoder (API 28+) decodes HEIC/HEIF (Apple), AVIF, animated WebP and 10-bit images
        // that BitmapFactory can't, and applies EXIF orientation itself. Fall back on older devices
        // or if it can't handle a particular file.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val src = ImageDecoder.createSource(app.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // so it can be JPEG-compressed
                    decoder.isMutableRequired = false
                    val longest = max(info.size.width, info.size.height)
                    if (longest > PREVIEW_PX) decoder.setTargetSampleSize(max(1, longest / PREVIEW_PX))
                }
            }.getOrNull()?.let { return it }
        }
        return decodePhotoLegacy(uri)
    }

    private fun decodePhotoLegacy(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_PX) sample *= 2
        val bmp = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = app.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { if (it !== bmp) bmp.recycle() }
    }.getOrNull()

    private fun videoFrame(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_PX, PREVIEW_PX)
            } else {
                r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun scaleDown(b: Bitmap, maxPx: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxPx) return b
        val f = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    companion object {
        /** Longest side of a preview. About 30–60 KB as a JPEG. */
        const val PREVIEW_PX = 480

        /** Keeps access to a folder picked with the system picker across restarts. */
        fun persistAccess(context: Context, uri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
