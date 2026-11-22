package com.psphere.core.shelf

import com.psphere.core.isValidHash
import com.psphere.core.net.Codec.shelfItem
import com.psphere.core.toHex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * One thing on the Shelf: a piece of text, a link, or a file, put there on one device for all the
 * linked devices to pick up.
 */
data class ShelfItem(
    val id: String,
    /** The device that put it on the Shelf. */
    val origin: String,
    val kind: Kind,
    /** The text or link; for a file, its name. Empty once removed. */
    val text: String,
    /** A file's SHA-256 ("" for text and links). */
    val hash: String = "",
    /** A file's size in bytes. */
    val size: Long = 0,
    val addedAt: Long,
    val pinned: Boolean = false,
    /** Taken off the Shelf. Kept as a marker so the removal reaches every device. */
    val removed: Boolean = false,
    /** When it last changed (added, pinned, unpinned or removed), on the device that changed it. */
    val changedAt: Long = addedAt,
) {
    enum class Kind { TEXT, LINK, FILE }

    val isFile: Boolean get() = kind == Kind.FILE

    /** Shape checks for an item from another device. */
    internal fun isValid(): Boolean =
        id.length in 1..64 && origin.length in 1..128 && size >= 0 &&
            (removed || kind != Kind.FILE || isValidHash(hash)) && (kind == Kind.FILE || hash.isEmpty())

    /** Which of two versions of the same item wins: a removal always does, otherwise the newer change. */
    internal fun mergedWith(other: ShelfItem): ShelfItem = when {
        removed -> this
        other.removed -> other
        other.changedAt > changedAt -> other
        else -> this
    }
}

/** An item as a screen shows it. */
data class ShelfEntry(
    val item: ShelfItem,
    val originName: String,
    /** For a file: its contents are on this device. Always true for text and links. */
    val here: Boolean,
)

/**
 * The Shelf on this device: its items (including removal markers) and the files they need, under
 * `<data>/shelf/`. Files are stored by their SHA-256, so the same file put on twice is kept once.
 * Thread-safe.
 */
class ShelfStore(private val dir: File, private val now: () -> Long) {
    private val listFile = File(dir, "shelf.bin")
    private val filesDir = File(dir, "files")
    private val items = LinkedHashMap<String, ShelfItem>()
    /** When the user last said to keep old items, so they aren't asked again right away. */
    private var keptOldAt = 0L

    init { load() }

    @Synchronized fun all(): List<ShelfItem> = items.values.toList()

    @Synchronized fun get(id: String): ShelfItem? = items[id]

    /** What's on the Shelf now (removal markers left out): pinned first, then newest first. */
    @Synchronized
    fun live(): List<ShelfItem> =
        items.values.filter { !it.removed }.sortedWith(compareByDescending<ShelfItem> { it.pinned }.thenByDescending { it.addedAt })

    fun hasFile(hash: String): Boolean = isValidHash(hash) && File(filesDir, hash).isFile

    fun file(hash: String): File? = File(filesDir, hash).takeIf { isValidHash(hash) && it.isFile }

    // ── Changes made on this device ──────────────────────────────────────────

    @Synchronized
    fun addText(origin: String, text: String): ShelfItem {
        val kind = if (looksLikeLink(text)) ShelfItem.Kind.LINK else ShelfItem.Kind.TEXT
        return put(ShelfItem(newId(), origin, kind, text, addedAt = now()))
    }

    /** Copies [input] into the Shelf's files and adds it. */
    fun addFile(origin: String, name: String, input: InputStream): ShelfItem {
        val (hash, size) = store(input)
        synchronized(this) {
            return put(ShelfItem(newId(), origin, ShelfItem.Kind.FILE, cleanName(name), hash, size, now()))
        }
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): ShelfItem? {
        val item = items[id]?.takeIf { !it.removed && it.pinned != pinned } ?: return null
        return put(item.copy(pinned = pinned, changedAt = maxOf(now(), item.changedAt + 1)))
    }

    /** Takes items off the Shelf, on every device once they hear about it. */
    @Synchronized
    fun remove(ids: Collection<String>): Int {
        var n = 0
        for (id in ids) {
            val item = items[id]?.takeIf { !it.removed } ?: continue
            put(tombstone(item))
            n++
        }
        if (n > 0) dropUnusedFiles()
        return n
    }

    /** Unpinned items put on the Shelf more than [OLD_MS] ago. */
    @Synchronized
    fun old(): List<ShelfItem> = live().filter { !it.pinned && now() - it.addedAt > OLD_MS }

    /** True when old items should be pointed out: there are some, and the user hasn't said to keep them lately. */
    @Synchronized
    fun shouldAskAboutOld(): Boolean = old().isNotEmpty() && now() - keptOldAt > OLD_MS

    @Synchronized
    fun keepOld() { keptOldAt = now(); save() }

    // ── Changes from other devices ───────────────────────────────────────────

    /**
     * Merges items another device sent. Items from devices not in [accepted] are ignored, so a
     * device that was unlinked doesn't come back through a third one.
     */
    @Synchronized
    fun merge(incoming: List<ShelfItem>, accepted: Set<String>): MergeResult {
        val added = ArrayList<ShelfItem>()
        var changed = false
        for (raw in incoming) {
            if (raw.origin !in accepted || !raw.isValid()) continue
            val theirs = if (raw.removed) tombstone(raw) else raw.copy(text = raw.text.take(MAX_TEXT_CHARS))
            val mine = items[theirs.id]
            if (mine != null && mine.origin != theirs.origin) continue // an id clash, not the same item
            val merged = mine?.mergedWith(theirs) ?: theirs
            if (merged == mine) continue
            items[merged.id] = merged
            changed = true
            if (mine == null && !merged.removed) added += merged
        }
        if (changed) { dropUnusedFiles(); save() }
        return MergeResult(changed, added)
    }

    /** [added]: items that are new here and on the Shelf. [changed]: anything changed at all. */
    data class MergeResult(val changed: Boolean, val added: List<ShelfItem>)

    /** Forgets everything [origin] put on the Shelf, e.g. when it's unlinked. No markers are left. */
    @Synchronized
    fun forgetOrigin(origin: String) {
        if (items.values.removeIf { it.origin == origin }) { dropUnusedFiles(); save() }
    }

    /**
     * Stores a file fetched from another device, if it's still wanted and its contents match.
     * [write] gets an output stream to fill.
     */
    fun storeFetched(hash: String, write: (java.io.OutputStream) -> Unit): File? {
        filesDir.mkdirs()
        val tmp = File(filesDir, "$hash.part")
        return try {
            tmp.outputStream().use(write)
            val got = tmp.inputStream().use { com.psphere.core.sha256Hex(it) }
            if (got != hash) throw IOException("checksum mismatch")
            val target = File(filesDir, hash)
            if (!target.isFile && !tmp.renameTo(target)) throw IOException("couldn't save")
            tmp.delete()
            target
        } catch (e: Exception) {
            tmp.delete(); null
        }
    }

    // ── Inside ───────────────────────────────────────────────────────────────

    private fun put(item: ShelfItem): ShelfItem {
        items[item.id] = item
        save()
        return item
    }

    private fun tombstone(item: ShelfItem) =
        item.copy(text = "", removed = true, pinned = false, changedAt = if (item.removed) item.changedAt else maxOf(now(), item.changedAt + 1))

    /** Copies [input] into the files folder, hashing as it goes. Returns the hash and size. */
    private fun store(input: InputStream): Pair<String, Long> {
        filesDir.mkdirs()
        val tmp = File(filesDir, "add-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); size += n
                }
            }
            val hash = digest.digest().toHex()
            val target = File(filesDir, hash)
            if (target.isFile) tmp.delete() else if (!tmp.renameTo(target)) throw IOException("Couldn't save the file")
            return hash to size
        } finally {
            tmp.delete()
        }
    }

    /** Deletes stored files no item on the Shelf needs any more. */
    private fun dropUnusedFiles() {
        val needed = items.values.filter { !it.removed && it.isFile }.map { it.hash }.toSet()
        filesDir.listFiles()?.forEach { f -> if (isValidHash(f.name) && f.name !in needed) f.delete() }
    }

    private fun load() {
        if (!listFile.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(listFile.inputStream())).use { input ->
                if (input.readInt() != FORMAT) return
                keptOldAt = input.readLong()
                repeat(input.readInt()) { input.shelfItem().let { items[it.id] = it } }
            }
        }.onFailure { items.clear() }
        // Removal markers only need to live long enough to reach every device.
        val cutoff = now() - MARKER_DAYS * DAY_MS
        items.values.removeIf { it.removed && it.changedAt < cutoff }
    }

    private fun save() {
        runCatching {
            dir.mkdirs()
            val tmp = File(listFile.path + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(FORMAT)
                out.writeLong(keptOldAt)
                out.writeInt(items.size)
                items.values.forEach { com.psphere.core.net.Codec.run { out.shelfItem(it) } }
            }
            if (!tmp.renameTo(listFile)) { listFile.delete(); tmp.renameTo(listFile) }
        }
    }

    companion object {
        const val DAY_MS = 24L * 3600 * 1000
        /** Items older than this are pointed out so the user can clear them. Nothing is cleared on its own. */
        const val OLD_MS = 7 * DAY_MS
        /** Files up to this size are copied to every device straight away; bigger ones when opened. */
        const val AUTO_FETCH_BYTES = 25L * 1024 * 1024
        /** Text longer than this (in UTF-8) is put on the Shelf as a .txt file instead. */
        const val MAX_TEXT_BYTES = 32 * 1024
        internal const val MAX_TEXT_CHARS = MAX_TEXT_BYTES
        private const val MARKER_DAYS = 180L
        private const val FORMAT = 0x50534831

        private val LINK = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)
        fun looksLikeLink(text: String) = LINK.matches(text.trim())

        private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

        private fun cleanName(name: String) =
            com.psphere.core.SafeNames.segment(name.substringAfterLast('/').substringAfterLast('\\'), "File")
    }
}
