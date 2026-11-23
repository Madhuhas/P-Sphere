package com.psphere.core.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads a release file for a one-click update and checks it against the SHA-256 GitHub lists
 * for it, so a broken or swapped download is never installed. Runs only after the user taps
 * Update. Blocking: call it off the main thread.
 */
object UpdateDownload {
    class Cancelled : IOException("Cancelled")

    private const val TIMEOUT_MS = 20_000

    /**
     * Downloads [asset] into [dir] and returns the checked file. A file already there with the
     * right SHA-256 is reused. [progress] gets (bytes so far, total); [cancelled] is polled while
     * downloading and stops it with [Cancelled].
     */
    fun download(asset: UpdateCheck.Asset, dir: File, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): File {
        val expected = asset.sha256 ?: throw IOException("GitHub doesn't list a checksum for ${asset.name}, so it can't be checked.")
        val uri = URI(asset.url)
        if (uri.scheme != "https" && !(UpdateCheck.isTesting && uri.scheme == "http")) throw IOException("Refusing to download over ${uri.scheme}.")
        val name = asset.name.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) && !it.startsWith(".") }
            ?: throw IOException("Unexpected file name ${asset.name}.")
        dir.mkdirs()
        val target = File(dir, name)
        if (target.isFile && sha256(target) == expected) { progress(target.length(), target.length()); return target }
        // Old downloads of other versions aren't needed any more.
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }

        val part = File(dir, "$name.part")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PSphere-update")
        }
        try {
            if (conn.responseCode != 200) throw IOException("The download failed (HTTP ${conn.responseCode}).")
            // GitHub redirects to its file storage; make sure that didn't drop to plain http.
            if (conn.url.protocol != "https" && !UpdateCheck.isTesting) throw IOException("Refusing to download over ${conn.url.protocol}.")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > asset.size + 1_000_000) throw IOException("The download is bigger than expected.")
                        if (done - lastReport >= 256 * 1024) { lastReport = done; progress(done, total) }
                    }
                }
            }
            progress(done, total)
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != expected) throw IOException("The download didn't match the checksum GitHub lists for it, so it wasn't installed. Try again.")
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save the download.")
            return target
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
