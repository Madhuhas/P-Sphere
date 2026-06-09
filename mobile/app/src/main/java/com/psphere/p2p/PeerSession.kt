package com.psphere.p2p

import android.util.Log
import com.psphere.sync.FileHasher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * Runs a bidirectional sync session over an open TCP socket.
 *
 * Protocol (newline-delimited JSON + raw bytes for file payloads):
 *
 *   CLIENT → HELLO {deviceId, hashes:[...]}
 *   SERVER ← HELLO {deviceId, hashes:[...]}
 *   CLIENT → NEED  {hashes:[...]}        (what I want server to send me)
 *   SERVER ← NEED  {hashes:[...]}        (what server wants me to send)
 *   SERVER → FILE  {hash, name, size} + [bytes]   (server pushes what client NEEDed)
 *   CLIENT ← ACK   {hash}
 *   CLIENT → FILE  {hash, name, size} + [bytes]   (client pushes what server NEEDed)
 *   SERVER ← ACK   {hash}
 *   CLIENT → BYE
 *   SERVER ← BYE
 *
 * The turn-based order guarantees no concurrent writes on the socket and eliminates
 * deadlock without any additional threading.
 */
class PeerSession(
    private val socket: Socket,
    private val localDeviceId: String,
    private val localFiles: List<FileHasher.FileEntry>,
    private val receiveDir: File,
    private val isServer: Boolean,
) {
    private val inputStream  = socket.getInputStream()
    private val out: OutputStream = socket.getOutputStream()

    init {
        socket.soTimeout = 30_000  // 30 s per-read timeout
    }

    var uploaded   = 0; private set
    var downloaded = 0; private set
    val uploadedPaths = mutableListOf<String>()   // absolute paths sent to peer (for free-up-space)

    fun run() {
        try {
            if (isServer) runAsServer() else runAsClient()
        } catch (e: Exception) {
            Log.e(TAG, "Session error (${if (isServer) "server" else "client"})", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Client flow ──────────────────────────────────────────────────────────

    private fun runAsClient() {
        val myHashes = localFiles.map { it.hash }

        sendJson(buildHello(myHashes))

        val peerHello  = readJson()
        val peerHashes = peerHello.getJSONArray("hashes").toStringSet()

        val myHashSet = myHashes.toHashSet()
        val iNeed     = peerHashes.filter { it !in myHashSet }
        sendJson(JSONObject().put("type", "NEED").put("hashes", JSONArray(iNeed)))

        val serverNeed = readJson().getJSONArray("hashes").toStringSet()

        repeat(iNeed.size)     { receiveFile(); downloaded++ }

        val toSend = localFiles.filter { it.hash in serverNeed }
        for (entry in toSend) { sendFile(entry); uploaded++ }

        sendJson(JSONObject().put("type", "BYE"))
        readJson()
    }

    // ── Server flow ──────────────────────────────────────────────────────────

    private fun runAsServer() {
        val myHashes = localFiles.map { it.hash }

        val clientHello  = readJson()
        val clientHashes = clientHello.getJSONArray("hashes").toStringSet()

        sendJson(buildHello(myHashes))

        val clientNeed = readJson().getJSONArray("hashes").toStringSet()

        val myHashSet = myHashes.toHashSet()
        val iNeed     = clientHashes.filter { it !in myHashSet }
        sendJson(JSONObject().put("type", "NEED").put("hashes", JSONArray(iNeed)))

        val toSend = localFiles.filter { it.hash in clientNeed }
        for (entry in toSend) { sendFile(entry); uploaded++ }

        repeat(iNeed.size)     { receiveFile(); downloaded++ }

        readJson()
        sendJson(JSONObject().put("type", "BYE"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sendFile(entry: FileHasher.FileEntry) {
        if (!entry.file.exists()) {
            Log.w(TAG, "Skipping missing file: ${entry.file.name}")
            return
        }
        sendJson(JSONObject().apply {
            put("type", "FILE")
            put("hash", entry.hash)
            put("name", entry.file.name)
            put("size", entry.file.length())
        })
        entry.file.inputStream().use { it.copyTo(out) }
        out.flush()
        readJson() // ACK
        uploadedPaths.add(entry.file.absolutePath)
        Log.d(TAG, "Sent ${entry.file.name} (${entry.hash.take(8)})")
    }

    private fun receiveFile() {
        val meta = readJson()
        val hash = meta.getString("hash")
        val name = meta.getString("name")
        val size = meta.getLong("size")

        receiveDir.mkdirs()
        // Avoid overwriting an existing file with a different hash — use a unique name
        var dest = File(receiveDir, name)
        if (dest.exists()) {
            val existing = FileHasher.sha256(dest)
            if (existing == hash) {
                // Already have this exact file — skip the bytes but send ACK
                var remaining = size
                val buf = ByteArray(8192)
                while (remaining > 0) {
                    val n = inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Unexpected EOF draining duplicate")
                    remaining -= n
                }
                sendJson(JSONObject().put("type", "ACK").put("hash", hash))
                Log.d(TAG, "Skipped duplicate $name (${hash.take(8)})")
                return
            }
            // Different content — use a unique filename
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.')
            dest = File(receiveDir, "${base}_${hash.take(8)}${if (ext.isNotEmpty()) ".$ext" else ""}")
        }
        var success = false
        try {
            val buf = ByteArray(8192)
            var remaining = size
            dest.outputStream().use { fos ->
                while (remaining > 0) {
                    val n = inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Unexpected EOF: got ${size - remaining}/$size bytes")
                    fos.write(buf, 0, n)
                    remaining -= n
                }
            }
            sendJson(JSONObject().put("type", "ACK").put("hash", hash))
            Log.d(TAG, "Received $name (${hash.take(8)})")
            success = true
        } finally {
            if (!success) {
                dest.delete()
                Log.w(TAG, "Deleted partial file: $name")
            }
        }
    }

    private fun buildHello(hashes: List<String>) = JSONObject().apply {
        put("type", "HELLO")
        put("deviceId", localDeviceId)
        put("hashes", JSONArray(hashes))
    }

    private fun sendJson(obj: JSONObject) {
        out.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Reads one newline-terminated JSON line byte-by-byte from the raw InputStream.
     * A BufferedReader must NOT be used here because its internal buffer would consume
     * bytes belonging to subsequent binary file payloads.
     */
    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = inputStream.read()
            if (c == -1) throw IOException("Connection closed unexpectedly")
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private fun readJson() = JSONObject(readLine())

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapTo(HashSet()) { getString(it) }

    companion object { private const val TAG = "PeerSession" }
}
