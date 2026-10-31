package com.psphere.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class FileInfo(
    /** '/'-separated path inside the folder. */
    val relPath: String,
    val size: Long,
    val modified: Long,
)

/**
 * A folder the app can read and write by relative path. Windows (and the tests) use
 * [FileSystemFolder]; Android implements it on top of the system folder picker's tree URIs.
 *
 * Paths passed in are always relative and '/'-separated. Hidden entries (names starting with '.')
 * are never listed: PSphere keeps its own working files under such names.
 */
interface Folder {
    fun list(): List<FileInfo>
    /** Size and modified time of one file, or null if it isn't there. */
    fun info(relPath: String): FileInfo?
    fun length(relPath: String): Long? = info(relPath)?.size
    fun openRead(relPath: String): InputStream
    /** Creates parent folders as needed. */
    fun openWrite(relPath: String, append: Boolean = false): OutputStream
    /** Moves a file inside this folder, creating parent folders. Fails if [to] exists. */
    fun move(from: String, to: String): Boolean
    fun delete(relPath: String): Boolean
    /** Bytes free on the drive holding this folder. */
    fun freeSpace(): Long
    /** Shown to the user, e.g. "D:\Backups". */
    val displayName: String

    fun exists(relPath: String) = length(relPath) != null
}

class FileSystemFolder(val root: File) : Folder {
    private val rootPath: String = root.canonicalFile.path

    override val displayName: String get() = root.path

    private fun resolve(relPath: String): File {
        val f = File(root, relPath.replace('/', File.separatorChar))
        val canon = f.canonicalFile.path
        if (canon != rootPath && !canon.startsWith(rootPath + File.separator)) throw IOException("Path outside folder: $relPath")
        return f
    }

    override fun list(): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.name.startsWith(".")) continue
                val rel = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                if (c.isDirectory) walk(c, rel)
                else if (c.isFile && c.canRead()) out += FileInfo(rel, c.length(), c.lastModified())
            }
        }
        walk(root, "")
        return out
    }

    override fun info(relPath: String): FileInfo? =
        resolve(relPath).takeIf { it.isFile }?.let { FileInfo(relPath, it.length(), it.lastModified()) }

    override fun openRead(relPath: String): InputStream = resolve(relPath).inputStream()

    override fun openWrite(relPath: String, append: Boolean): OutputStream {
        val f = resolve(relPath)
        f.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Couldn't create folder ${it.path}") }
        return FileOutputStream(f, append)
    }

    override fun move(from: String, to: String): Boolean {
        val src = resolve(from)
        val dst = resolve(to)
        if (dst.exists()) return false
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        // Different drive or a locked rename: fall back to copy + delete.
        return runCatching { src.copyTo(dst); src.delete(); true }.getOrDefault(false)
    }

    override fun delete(relPath: String): Boolean = resolve(relPath).let { !it.exists() || it.delete() }

    override fun freeSpace(): Long = root.usableSpace
}
