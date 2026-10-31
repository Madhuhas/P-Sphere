package com.psphere.core.storage

import com.psphere.core.ProtocolException
import com.psphere.core.SafeNames
import com.psphere.core.copyExactly
import com.psphere.core.net.Codec.str
import com.psphere.core.toHex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a receiver needs to know to place an incoming file. */
data class Incoming(
    val hash: String,
    val size: Long,
    val origin: String,
    val originName: String,
    val ruleId: String,
    val ruleLabel: String,
    val relPath: String,
)

data class KeptEntry(
    val hash: String,
    /** Path inside the backup location. */
    val path: String,
    val origin: String,
    val ruleId: String,
    val relPath: String,
    val size: Long,
)

sealed class ReceiveResult {
    data class Stored(val entry: KeptEntry) : ReceiveResult()
    data class Rejected(val reason: String) : ReceiveResult()
}

/**
 * Backups this device keeps for other devices, under the backup location:
 *
 *     <location>/<origin device>/<folder>/<path inside folder>
 *
 * Folder names are fixed the first time a device or folder is seen, so renaming a device later
 * doesn't move anything. Working files live in `<location>/.psphere/`:
 * the index, partial downloads (`tmp/<hash>.part`, kept so transfers can resume) and replaced
 * versions (`old/<date>/…`, deleted after [OLD_VERSION_DAYS]).
 */
class KeptStore(
    val root: Folder,
    /** Never fill the drive past this many free bytes. */
    var reserveBytes: Long = DEFAULT_RESERVE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val byItem = HashMap<String, KeptEntry>()
    private val byHash = HashMap<String, MutableSet<String>>()
    private val originFolders = HashMap<String, String>()
    private val ruleFolders = HashMap<String, String>()
    private val oldVersions = ArrayList<Pair<String, Long>>()
    private val inFlight = HashSet<String>()
    private var dirty = false

    init { load() }

    private fun itemKey(origin: String, ruleId: String, relPath: String) = "$origin\u0000$ruleId\u0000$relPath"

    // ── Queries ──────────────────────────────────────────────────────────────

    @Synchronized fun has(hash: String): Boolean = entryFor(hash) != null

    @Synchronized fun entries(): List<KeptEntry> = byItem.values.toList()

    @Synchronized fun entry(origin: String, ruleId: String, relPath: String): KeptEntry? = byItem[itemKey(origin, ruleId, relPath)]

    /** An entry for [hash] whose file is still there with the right size. */
    @Synchronized
    fun entryFor(hash: String): KeptEntry? =
        byHash[hash].orEmpty().mapNotNull { byItem[it] }.firstOrNull { root.length(it.path) == it.size }

    fun openRead(hash: String): Pair<KeptEntry, InputStream>? {
        val e = entryFor(hash) ?: return null
        return e to root.openRead(e.path)
    }

    /** Bytes already downloaded for [hash] by an interrupted transfer. */
    fun partialLength(hash: String): Long = root.length(partPath(hash)) ?: 0

    fun canFit(bytes: Long): Boolean = root.freeSpace() - bytes >= reserveBytes

    // ── Receiving ────────────────────────────────────────────────────────────

    /** Reserves [hash] so two sessions don't download the same file at once. */
    @Synchronized fun claim(hash: String): Boolean = inFlight.add(hash)
    @Synchronized fun release(hash: String) { inFlight.remove(hash) }

    /**
     * Reads [length] bytes of [meta] starting at [offset] from [input], verifies the whole file
     * and puts it in place. An IOException while reading leaves the partial file for resuming.
     */
    fun receive(meta: Incoming, offset: Long, length: Long, input: InputStream): ReceiveResult {
        val part = partPath(meta.hash)
        val have = root.length(part) ?: 0
        if (offset != 0L && offset != have) {
            com.psphere.core.skipExactly(input, length)
            root.delete(part)
            return ReceiveResult.Rejected("resume point changed")
        }
        if (offset == 0L && have > 0) root.delete(part)
        if (offset + length != meta.size) {
            com.psphere.core.skipExactly(input, length)
            return ReceiveResult.Rejected("size mismatch")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) root.openRead(part).use { copyExactly(it, NullOut, offset, digest) }
        root.openWrite(part, append = offset > 0).use { out ->
            copyExactly(input, out, length, digest)
        }
        if (digest.digest().toHex() != meta.hash) {
            root.delete(part)
            return ReceiveResult.Rejected("checksum mismatch")
        }
        return commit(meta, part)
    }

    @Synchronized
    private fun commit(meta: Incoming, part: String): ReceiveResult {
        val target = targetPath(meta)
        val key = itemKey(meta.origin, meta.ruleId, meta.relPath)
        val existing = byItem[key]

        if (existing != null && existing.hash == meta.hash && root.length(existing.path) == meta.size) {
            root.delete(part)
            return ReceiveResult.Stored(existing)
        }
        // A different version (or a file someone put there) is never overwritten: it moves to old/.
        if (root.exists(target)) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now()))
            var old = "$OLD_DIR/$day/$target"
            var n = 2
            while (root.exists(old)) old = "$OLD_DIR/$day/${withSuffix(target, n++)}"
            if (!root.move(target, old)) return ReceiveResult.Rejected("couldn't move the previous version aside")
            oldVersions += old to now()
        }
        if (!root.move(part, target)) return ReceiveResult.Rejected("couldn't save the file")

        existing?.let { unindex(key, it) }
        val entry = KeptEntry(meta.hash, target, meta.origin, meta.ruleId, meta.relPath, meta.size)
        byItem[key] = entry
        byHash.getOrPut(meta.hash) { HashSet() } += key
        dirty = true
        return ReceiveResult.Stored(entry)
    }

    private fun unindex(key: String, e: KeptEntry) {
        byItem -= key
        byHash[e.hash]?.let { it -= key; if (it.isEmpty()) byHash -= e.hash }
    }

    private fun targetPath(meta: Incoming): String {
        val device = originFolders.getOrPut(meta.origin) {
            unique(SafeNames.segment(meta.originName, meta.origin), originFolders.values.toSet()).also { dirty = true }
        }
        val folder = ruleFolders.getOrPut("${meta.origin}/${meta.ruleId}") {
            val taken = ruleFolders.filterKeys { it.startsWith("${meta.origin}/") }.values.toSet()
            unique(SafeNames.segment(meta.ruleLabel, meta.ruleId), taken).also { dirty = true }
        }
        return "$device/$folder/${SafeNames.relativePath(meta.relPath, meta.hash.take(16))}"
    }

    private fun unique(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken.map { it.lowercase() }) return name
        var n = 2
        while ("$name ($n)".lowercase() in taken.map { it.lowercase() }) n++
        return "$name ($n)"
    }

    private fun withSuffix(path: String, n: Int): String {
        val dot = path.lastIndexOf('.')
        return if (dot > path.lastIndexOf('/')) "${path.substring(0, dot)} ($n)${path.substring(dot)}" else "$path ($n)"
    }

    // ── Housekeeping ─────────────────────────────────────────────────────────

    /** Deletes replaced versions older than [OLD_VERSION_DAYS]. */
    @Synchronized
    fun purgeOldVersions() {
        val cutoff = now() - OLD_VERSION_DAYS * 24L * 3600 * 1000
        val expired = oldVersions.filter { it.second < cutoff }
        expired.forEach { root.delete(it.first) }
        if (expired.isNotEmpty()) { oldVersions -= expired.toSet(); dirty = true }
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val buf = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(buf)).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(originFolders.size); originFolders.forEach { (k, v) -> out.str(k); out.str(v) }
            out.writeInt(ruleFolders.size); ruleFolders.forEach { (k, v) -> out.str(k); out.str(v) }
            out.writeInt(byItem.size)
            byItem.values.forEach { e ->
                out.str(e.hash); out.str(e.path); out.str(e.origin); out.str(e.ruleId); out.str(e.relPath); out.writeLong(e.size)
            }
            out.writeInt(oldVersions.size); oldVersions.forEach { (p, t) -> out.str(p); out.writeLong(t) }
        }
        root.openWrite(INDEX_TMP).use { it.write(buf.toByteArray()) }
        root.delete(INDEX)
        if (!root.move(INDEX_TMP, INDEX)) throw IOException("Couldn't save the backup index")
        dirty = false
    }

    private fun load() {
        val path = if (root.exists(INDEX)) INDEX else if (root.exists(INDEX_TMP)) INDEX_TMP else return
        DataInputStream(BufferedInputStream(root.openRead(path))).use { input ->
            if (input.readInt() != FORMAT) throw ProtocolException("Unknown backup index format")
            repeat(input.readInt()) { originFolders[input.str()] = input.str() }
            repeat(input.readInt()) { ruleFolders[input.str()] = input.str() }
            repeat(input.readInt()) {
                val e = KeptEntry(input.str(), input.str(), input.str(), input.str(), input.str(), input.readLong())
                val key = itemKey(e.origin, e.ruleId, e.relPath)
                byItem[key] = e
                byHash.getOrPut(e.hash) { HashSet() } += key
            }
            repeat(input.readInt()) { oldVersions += input.str() to input.readLong() }
        }
    }

    private fun partPath(hash: String) = "$TMP_DIR/$hash.part"

    private object NullOut : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        const val DEFAULT_RESERVE = 1L shl 30
        const val OLD_VERSION_DAYS = 30
        private const val FORMAT = 1
        private const val WORK_DIR = ".psphere"
        private const val TMP_DIR = "$WORK_DIR/tmp"
        private const val OLD_DIR = "$WORK_DIR/old"
        private const val INDEX = "$WORK_DIR/index.bin"
        private const val INDEX_TMP = "$WORK_DIR/index.bin.tmp"
    }
}
