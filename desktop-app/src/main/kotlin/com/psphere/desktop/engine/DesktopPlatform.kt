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
import java.io.File
import java.util.TimeZone

/** The desktop side of the shared core (Windows, macOS, Linux): folders are plain paths; previews and dates come from the files. */
class DesktopPlatform : Platform {

    override fun openFolder(location: String): Folder? {
        val dir = File(location)
        return if (dir.isDirectory) FileSystemFolder(dir) else null
    }

    override fun makePreview(folder: Folder, relPath: String, media: Media): ByteArray? {
        // Videos and formats Java can't read (HEIC, RAW) get no preview here; the phone that
        // took them usually sends one along with the catalog.
        if (media != Media.PHOTO) return null
        val file = fileOf(folder, relPath) ?: return null
        return Images.readUpright(file, PREVIEW_PX)?.let { Images.jpeg(it) }
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

    companion object {
        /** Longest side of a preview, the same as on Android. About 30–60 KB as a JPEG. */
        const val PREVIEW_PX = 480
    }
}
