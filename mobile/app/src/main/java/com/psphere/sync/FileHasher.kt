package com.psphere.sync

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object FileHasher {

    data class FileEntry(
        val file: File,
        val relativePath: String,
        val hash: String,
        val modifiedAt: Long,
    )

    // In-memory cache keyed by absolute path.
    // Entry is valid as long as size and modifiedAt are unchanged.
    private data class CacheEntry(val hash: String, val size: Long, val modifiedAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var n: Int
            while (stream.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Returns the hash for [file], using the in-memory cache if size and mtime haven't changed. */
    fun cachedHash(file: File): String {
        val key    = file.absolutePath
        val size   = file.length()
        val mtime  = file.lastModified()
        val cached = cache[key]
        if (cached != null && cached.size == size && cached.modifiedAt == mtime) {
            return cached.hash
        }
        val hash = sha256(file)
        cache[key] = CacheEntry(hash, size, mtime)
        return hash
    }

    /** Clears the hash cache. Call after a sync completes to free memory. */
    fun clearCache() = cache.clear()

    fun scanDirectory(rootDir: File): List<FileEntry> =
        rootDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                FileEntry(
                    file         = file,
                    relativePath = file.relativeTo(rootDir).path,
                    hash         = cachedHash(file),
                    modifiedAt   = file.lastModified(),
                )
            }
            .toList()
}
