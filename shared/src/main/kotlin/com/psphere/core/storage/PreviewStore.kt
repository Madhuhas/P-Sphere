package com.psphere.core.storage

import com.psphere.core.isValidHash
import java.io.File

/** Small JPEG previews of library items, keyed by the full file's hash. Lives in app-private storage. */
class PreviewStore(private val dir: File) {
    private fun fileFor(hash: String): File {
        require(isValidHash(hash)) { "Invalid hash" }
        return File(File(dir, hash.take(2)), "$hash.jpg")
    }

    fun has(hash: String) = fileFor(hash).isFile
    fun get(hash: String): ByteArray? = fileFor(hash).takeIf { it.isFile }?.readBytes()
    fun file(hash: String): File? = fileFor(hash).takeIf { it.isFile }

    fun put(hash: String, data: ByteArray) {
        val f = fileFor(hash)
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun totalBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
