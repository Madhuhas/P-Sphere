package com.psphere.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Thrown when a peer breaks the protocol. The message is safe to show to the user. */
class ProtocolException(message: String) : IOException(message)

internal const val BUFFER = 64 * 1024

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(chars)
}
private val HEX = "0123456789abcdef".toCharArray()

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(BUFFER)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
    }
    return digest.digest().toHex()
}

private val HASH = Regex("^[0-9a-f]{64}$")
fun isValidHash(h: String) = HASH.matches(h)

/**
 * Puts the freshly written [tmp] in place of [target] in one step, so an app killed or a power cut
 * mid-save never leaves neither file (deleting first and renaming after would).
 */
internal fun replaceFile(tmp: java.io.File, target: java.io.File) {
    val from = tmp.toPath(); val to = target.toPath()
    try {
        java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

/** The saved [file], or the new copy an interrupted save left next to it. Null if neither is there. */
internal fun savedFile(file: java.io.File): java.io.File? =
    file.takeIf { it.isFile } ?: java.io.File(file.path + ".tmp").takeIf { it.isFile }

/** Copies exactly [size] bytes, failing if the stream ends early. */
internal fun copyExactly(from: InputStream, to: OutputStream, size: Long, digest: MessageDigest? = null) {
    val buf = ByteArray(BUFFER)
    var remaining = size
    while (remaining > 0) {
        val n = from.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw IOException("Connection ended after ${size - remaining} of $size bytes")
        to.write(buf, 0, n)
        digest?.update(buf, 0, n)
        remaining -= n
    }
}

internal fun skipExactly(from: InputStream, size: Long) {
    val buf = ByteArray(BUFFER)
    var remaining = size
    while (remaining > 0) {
        val n = from.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw IOException("Connection ended while skipping data")
        remaining -= n
    }
}

/** Naming rules for anything a peer can influence that ends up as a file or folder name. */
object SafeNames {
    private val ILLEGAL = Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]")
    private val RESERVED = setOf("con", "prn", "aux", "nul") +
        (1..9).flatMap { listOf("com$it", "lpt$it") }

    /** One path segment: no separators, no characters Windows or Android storage reject. */
    fun segment(raw: String, fallback: String): String {
        var clean = ILLEGAL.replace(raw, "_").trim().trimStart('.').trimEnd('.', ' ').trim()
        if (clean.substringBefore('.').lowercase() in RESERVED) clean = "_$clean"
        if (clean.isEmpty()) return fallback
        if (clean.length <= 120) return clean
        val ext = clean.substringAfterLast('.', "").take(10)
        return clean.take(110) + if (ext.isNotEmpty()) ".$ext" else ""
    }

    /**
     * A relative path made only of safe segments joined with '/'. Empty, "." and ".." segments are
     * dropped, so the result can never climb out of the folder it is resolved against.
     */
    fun relativePath(raw: String, fallback: String): String {
        val parts = raw.split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .map { segment(it, "_") }
        return if (parts.isEmpty()) fallback else parts.joinToString("/")
    }
}
