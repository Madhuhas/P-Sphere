package com.psphere.core.node

import com.psphere.core.ProtocolException
import com.psphere.core.catalog.Catalog
import com.psphere.core.copyExactly
import com.psphere.core.identity.DeviceIdentity
import com.psphere.core.identity.Fingerprint
import com.psphere.core.isValidHash
import com.psphere.core.model.DeviceRec
import com.psphere.core.model.HoldRec
import com.psphere.core.model.Include
import com.psphere.core.model.ItemRec
import com.psphere.core.model.ItemState
import com.psphere.core.model.Media
import com.psphere.core.model.RuleRec
import com.psphere.core.net.Msg
import com.psphere.core.net.OfferEntry
import com.psphere.core.net.Tls
import com.psphere.core.net.Wire
import com.psphere.core.sha256Hex
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.core.shelf.ShelfStore
import com.psphere.core.storage.FileInfo
import com.psphere.core.storage.Folder
import com.psphere.core.storage.Incoming
import com.psphere.core.storage.KeptStore
import com.psphere.core.storage.PreviewStore
import com.psphere.core.storage.ReceiveResult
import com.psphere.core.toHex
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket

/** What the shared core needs from Android or Windows. */
interface Platform {
    /** Opens a folder from a stored location (a path on Windows, a tree URI on Android). */
    fun openFolder(location: String): Folder?
    /** A small JPEG preview (around 50 KB) of a photo or video, or null if it can't make one. */
    fun makePreview(folder: Folder, relPath: String, media: Media): ByteArray? = null
    /** When the photo was taken, if the platform can tell (EXIF, MediaStore). */
    fun takenAt(folder: Folder, info: FileInfo, media: Media): Long? = null
}

data class HostPort(val host: String, val port: Int)

data class ScanResult(val files: Int, val changed: Int, val deleted: Int, val errors: List<String>)

data class LibraryItem(
    val item: ItemRec,
    val originName: String,
    /** The full file is on this device (its own file, or a backup kept here). */
    val onThisDevice: Boolean,
    /** Devices that hold a full copy. */
    val holders: Set<String>,
)

data class KeptGroup(
    val origin: String,
    val originName: String,
    val ruleId: String,
    val label: String,
    /** Folder inside the backup location, e.g. "Phone/Camera". */
    val path: String,
    val files: Int,
    val bytes: Long,
)

data class FreeUpPlan(val items: List<ItemRec>, val bytes: Long)

data class FreeUpResult(
    val freed: Int,
    val bytes: Long,
    /** Files left alone, with the reason. */
    val skipped: List<Pair<ItemRec, String>>,
    /** Devices that were offline, so their earlier confirmation was trusted. */
    val notRechecked: Set<String>,
)

/** A readable full copy of a file on this device. */
class LocalSource(val size: Long, val stream: InputStream) : Closeable {
    override fun close() = stream.close()
}

/**
 * Everything PSphere does on one device, independent of Android or Windows: identity and linked
 * devices, this device's backup folders, the replicated catalog, backups kept for other devices,
 * previews, syncing, linking, Free up space and the library.
 *
 * Thread-safe. Network calls block, so call them off the UI thread.
 */
class Node(
    private val dataDir: File,
    defaultName: String,
    private val platform: Platform,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val identity: DeviceIdentity = DeviceIdentity.loadOrCreate(File(dataDir, "identity.bin"))
    val id: String = identity.deviceId
    val tls = Tls(identity)
    private val stateFile = File(dataDir, "state.bin")
    private val catalogFile = File(dataDir, "catalog.bin")
    val state: LocalState = LocalState.load(stateFile, defaultName)
    val catalog: Catalog = Catalog.load(catalogFile, id)
    val previews = PreviewStore(File(dataDir, "previews"))
    private val cacheDir = File(dataDir, "cache")

    @Volatile private var kept: KeptStore? = null
    private val peerLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val scanLock = ReentrantLock()

    /** Called (on any thread) whenever something the UI shows may have changed. */
    @Volatile var onChange: (() -> Unit)? = null
    /** Asks the user to compare a code when another device links without a QR code. */
    @Volatile var codeConfirmer: CodeConfirmer? = null
    /** Progress of a sync another device started with this one (on that connection's thread). */
    @Volatile var onIncomingProgress: ((SyncProgress) -> Unit)? = null
    /** Called when a sync another device started has ended, with its result. */
    @Volatile var onIncomingDone: ((SyncResult) -> Unit)? = null

    /** Where this device listens for connections; told to other devices in Hello. Set by [NodeServer]. */
    @Volatile var serverPort = 0

    /** Set by an app that has the smart features turned on. */
    @Volatile var smart: SmartLibrary? = null
    private val smartCache = SmartCache(File(dataDir, "smart-answers.bin"))

    /** Text, links and files put on the Shelf by any linked device. */
    val shelf = ShelfStore(File(dataDir, "shelf"), now)
    /** Called (on any thread) when another device puts something on the Shelf, e.g. to notify. */
    @Volatile var onShelfAdded: ((List<ShelfEntry>) -> Unit)? = null
    private val shelfWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "psphere-shelf").apply { isDaemon = true } }
    private val shelfLocks = ConcurrentHashMap<String, Any>()

    @Volatile private var pairing: PairingWindow? = null
    private class PairingWindow(val token: String, val expiresAt: Long, val allowCode: Boolean)

    init {
        catalog.putOwn(DeviceRec(id, 0, state.deviceName))
        openKept()
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    val deviceName: String get() = state.deviceName

    @Synchronized
    fun rename(name: String) {
        val clean = name.trim().take(60).ifEmpty { return }
        state.deviceName = clean
        catalog.putOwn(DeviceRec(id, 0, clean))
        save()
    }

    /** Where backups for other devices go. Existing backups are not moved. */
    @Synchronized
    fun setBackupLocation(location: String?) {
        kept?.flush()
        state.backupLocation = location
        openKept()
        save()
        // What this device holds has changed: republish holdings for both old and new contents.
        kept?.entries()?.forEach { refreshHold(it.hash) }
        catalog.items().filter { id in catalog.holders(it.hash) }.forEach { refreshHold(it.hash) }
        save()
    }

    fun backupFolder(): Folder? = kept?.root

    @Synchronized
    fun setReceivingPaused(paused: Boolean) { state.receivingPaused = paused; save() }

    @Synchronized
    fun setReserveBytes(bytes: Long) { state.reserveBytes = bytes; kept?.reserveBytes = bytes; save() }

    private fun openKept() {
        kept = state.backupLocation?.let { platform.openFolder(it) }?.let { KeptStore(it, state.reserveBytes, now) }
    }

    // ── Linked devices ───────────────────────────────────────────────────────

    fun devices(): List<LinkedDevice> = synchronized(this) { state.devices.values.toList() }
    fun device(id: String): LinkedDevice? = synchronized(this) { state.devices[id] }

    /** Current name of a device: what it last told us, or what we stored when linking. */
    fun nameOf(deviceId: String): String =
        if (deviceId == id) state.deviceName else catalog.deviceName(deviceId) ?: device(deviceId)?.name ?: deviceId

    @Synchronized
    fun unlink(deviceId: String) {
        state.devices.remove(deviceId) ?: return
        ownRules().forEach { (rule, _) ->
            if (deviceId in rule.destinations) catalog.putOwn(rule.copy(destinations = rule.destinations - deviceId))
        }
        // Forget the device's photos from the shared library, and drop previews nothing needs now.
        catalog.removeOrigin(deviceId).forEach { hash ->
            if (catalog.itemsWithHash(hash).isEmpty() && !holdsLocally(hash)) previews.remove(hash)
        }
        // And the people, places and searches it answered: they're its photos too.
        smartCache.forget(deviceId)
        // And what it put on the Shelf.
        shelf.forgetOrigin(deviceId)
        save()
    }

    @Synchronized
    private fun link(fp: Fingerprint, name: String, address: HostPort?): LinkedDevice {
        val d = LinkedDevice(fp.deviceId, name.trim().take(60).ifEmpty { fp.deviceId }, fp, now(), address?.host, address?.port ?: 0)
        state.devices[d.id] = d
        save()
        return d
    }

    @Synchronized
    fun noteAddress(deviceId: String, address: HostPort) {
        val d = state.devices[deviceId] ?: return
        if (d.lastHost == address.host && d.lastPort == address.port) return
        state.devices[deviceId] = d.copy(lastHost = address.host, lastPort = address.port)
        save()
    }

    @Synchronized
    private fun markSynced(deviceId: String) {
        val d = state.devices[deviceId] ?: return
        state.devices[deviceId] = d.copy(lastSyncAt = now())
        save()
    }

    /** How many files [peerId] should get from this device (optionally from one backup folder). */
    fun pendingCount(peerId: String, ruleId: String? = null): Int =
        offersFor(peerId).count { ruleId == null || (it.origin == id && it.ruleId == ruleId) }

    /** Backups this device keeps for others, one entry per device and folder. */
    fun keptGroups(): List<KeptGroup> {
        val store = kept ?: return emptyList()
        return store.entries().groupBy { it.origin to it.ruleId }.map { (key, entries) ->
            val (origin, ruleId) = key
            KeptGroup(
                origin = origin,
                originName = nameOf(origin),
                ruleId = ruleId,
                label = catalog.rule(origin, ruleId)?.label ?: entries.first().path.split('/').getOrElse(1) { ruleId },
                path = entries.first().path.split('/').take(2).joinToString("/"),
                files = entries.size,
                bytes = entries.sumOf { it.size },
            )
        }.sortedWith(compareBy({ it.originName.lowercase() }, { it.label.lowercase() }))
    }

    // ── This device's backup folders ─────────────────────────────────────────

    /** This device's backup folders with their local settings. Removed folders are left out. */
    fun ownRules(): List<Pair<RuleRec, LocalRule>> = synchronized(this) {
        catalog.rules(id).filter { !it.removed }.mapNotNull { r -> state.rules[r.ruleId]?.let { r to it } }
            .sortedBy { it.first.label.lowercase() }
    }

    @Synchronized
    fun addRule(label: String, location: String, destinations: Set<String>, include: Include, showInLibrary: Boolean = true): RuleRec {
        val ruleId = UUID.randomUUID().toString().take(8)
        val rule = RuleRec(id, 0, ruleId, label.trim().ifEmpty { "Folder" }, destinations - id, include, showInLibrary)
        state.rules[ruleId] = LocalRule(ruleId, location)
        catalog.putOwn(rule)
        save()
        return catalog.rule(id, ruleId)!!
    }

    @Synchronized
    fun updateRule(
        ruleId: String,
        label: String? = null,
        destinations: Set<String>? = null,
        include: Include? = null,
        showInLibrary: Boolean? = null,
        freeNeedsAllDestinations: Boolean? = null,
        keepRecentDays: Int? = null,
    ) {
        val rule = catalog.rule(id, ruleId) ?: return
        catalog.putOwn(rule.copy(
            label = label?.trim()?.ifEmpty { null } ?: rule.label,
            destinations = destinations?.minus(id) ?: rule.destinations,
            include = include ?: rule.include,
            showInLibrary = showInLibrary ?: rule.showInLibrary,
        ))
        state.rules[ruleId]?.let { l ->
            state.rules[ruleId] = l.copy(
                freeNeedsAllDestinations = freeNeedsAllDestinations ?: l.freeNeedsAllDestinations,
                keepRecentDays = keepRecentDays ?: l.keepRecentDays,
            )
        }
        save()
    }

    /** Points a backup folder at a different location on this device (e.g. after re-granting access). */
    @Synchronized
    fun setRuleLocation(ruleId: String, location: String) {
        val l = state.rules[ruleId] ?: return
        state.rules[ruleId] = l.copy(location = location)
        save()
    }

    fun ruleLocation(ruleId: String): Folder? = sourceFolder(ruleId)

    /** Stops backing up a folder. Copies already made elsewhere stay. */
    @Synchronized
    fun removeRule(ruleId: String) {
        val rule = catalog.rule(id, ruleId) ?: return
        catalog.putOwn(rule.copy(removed = true))
        state.rules.remove(ruleId)
        save()
    }

    private fun sourceFolder(ruleId: String): Folder? = state.rules[ruleId]?.let { platform.openFolder(it.location) }

    // ── Scanning ─────────────────────────────────────────────────────────────

    /**
     * Updates the catalog from this device's backup folders. Only changed files are re-read.
     * With [budgetMs], stops reading new files once the time is up; what was read is kept, and
     * nothing is marked deleted, since files not yet looked at may still be there.
     */
    fun scan(budgetMs: Long? = null, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ScanResult {
        scanLock.lock()
        try { return scanLocked(budgetMs, onProgress) } finally { scanLock.unlock() }
    }

    private fun scanLocked(budgetMs: Long?, onProgress: (done: Int, total: Int) -> Unit): ScanResult {
        val deadline = budgetMs?.let { System.nanoTime() + it * 1_000_000 }
        fun outOfTime() = deadline != null && System.nanoTime() > deadline
        var files = 0; var changed = 0; var deleted = 0
        val errors = ArrayList<String>()
        for ((rule, local) in ownRules()) {
            if (outOfTime()) break
            val folder = platform.openFolder(local.location)
            if (folder == null) { errors += "Can't open the folder for “${rule.label}”. Choose it again in Sending."; continue }
            val listed = try { folder.list() } catch (e: Exception) { errors += "Can't read “${rule.label}”: ${e.message}"; continue }
            val wanted = listed.filter { rule.include == Include.ALL || Media.of(it.relPath) != Media.OTHER }
            val seen = HashSet<String>()
            var complete = true
            wanted.forEachIndexed { i, f ->
                if (!complete) return@forEachIndexed
                onProgress(i, wanted.size)
                seen += f.relPath
                files++
                val existing = catalog.item(id, rule.ruleId, f.relPath)
                if (existing != null && existing.state == ItemState.PRESENT && existing.size == f.size && existing.modified == f.modified) {
                    refreshHold(existing.hash)
                    return@forEachIndexed
                }
                if (outOfTime()) { complete = false; return@forEachIndexed }
                val hash = runCatching { folder.openRead(f.relPath).use(::sha256Hex) }.getOrElse {
                    errors += "Couldn't read ${f.relPath}: ${it.message}"
                    return@forEachIndexed
                }
                val media = Media.of(f.relPath)
                val taken = runCatching { platform.takenAt(folder, f, media) }.getOrNull() ?: f.modified
                catalog.putOwn(ItemRec(id, 0, rule.ruleId, f.relPath, hash, f.size, f.modified, taken, media, ItemState.PRESENT))
                if (existing != null && existing.hash != hash) refreshHold(existing.hash)
                refreshHold(hash)
                if (media != Media.OTHER && rule.showInLibrary && !previews.has(hash)) makePreview(folder, f.relPath, media, hash)
                changed++
            }
            onProgress(wanted.size, wanted.size)
            if (!complete) break
            catalog.items(id).filter { it.ruleId == rule.ruleId && it.relPath !in seen && it.state == ItemState.PRESENT }.forEach {
                catalog.putOwn(it.copy(state = ItemState.DELETED))
                refreshHold(it.hash)
                deleted++
            }
        }
        save()
        if (changed + deleted > 0) onChange?.invoke()
        return ScanResult(files, changed, deleted, errors)
    }

    private fun makePreview(folder: Folder, relPath: String, media: Media, hash: String) {
        runCatching { platform.makePreview(folder, relPath, media) }.getOrNull()?.let { previews.put(hash, it) }
    }

    // ── What this device holds ───────────────────────────────────────────────

    /** Opens a full local copy of [hash]: one of this device's own files, or a backup kept here. */
    fun openLocal(hash: String): LocalSource? {
        for (item in catalog.itemsWithHash(hash)) {
            if (item.origin != id || item.state != ItemState.PRESENT) continue
            val folder = sourceFolder(item.ruleId) ?: continue
            if (folder.length(item.relPath) != item.size) continue
            return runCatching { LocalSource(item.size, folder.openRead(item.relPath)) }.getOrNull() ?: continue
        }
        kept?.openRead(hash)?.let { (entry, stream) -> return LocalSource(entry.size, stream) }
        // A file on the Shelf, so other devices can fetch it with Get.
        shelf.file(hash)?.let { f -> return runCatching { LocalSource(f.length(), f.inputStream()) }.getOrNull() }
        return null
    }

    fun holdsLocally(hash: String): Boolean {
        val own = catalog.itemsWithHash(hash).any { it.origin == id && it.state == ItemState.PRESENT }
        return own || kept?.has(hash) == true
    }

    /** Publishes whether this device holds [hash], so other devices know where copies are. */
    private fun refreshHold(hash: String) {
        val present = holdsLocally(hash)
        if (!present && id !in catalog.holders(hash)) return
        catalog.putOwn(HoldRec(id, 0, hash, present))
    }

    // ── Sync: deciding what goes where ───────────────────────────────────────

    /** Files [peerId] should get from this device: its own, and copies it keeps that [peerId] is also meant to have. */
    internal fun offersFor(peerId: String): List<OfferEntry> {
        val out = ArrayList<OfferEntry>()
        val seen = HashSet<String>()
        for (item in catalog.items()) {
            if (item.origin == peerId) continue
            val rule = catalog.rule(item.origin, item.ruleId) ?: continue
            if (rule.removed || peerId !in rule.destinations) continue
            // Holdings are per content: a peer that already has these bytes (under any name) is done.
            if (peerId in catalog.holders(item.hash)) continue
            if (!holdsLocally(item.hash)) continue
            if (seen.add(item.origin + item.key)) out += OfferEntry(item.hash, item.size, item.origin, item.ruleId, item.relPath)
        }
        return out
    }

    internal fun planIncoming(peer: LinkedDevice, offer: List<OfferEntry>): IncomingPlan {
        val store = kept
        val notes = ArrayList<String>()
        if (offer.isEmpty()) return IncomingPlan(emptyList(), notes)
        if (store == null) return IncomingPlan(emptyList(), listOf("${state.deviceName} has no backup location set"))
        if (state.receivingPaused) return IncomingPlan(emptyList(), listOf("Receiving is paused on ${state.deviceName}"))

        val wanted = ArrayList<Wanted>()
        var bytesNeeded = 0L
        var full = false
        offer.forEachIndexed { index, e ->
            if (!isValidHash(e.hash) || e.size < 0 || e.origin == id) return@forEachIndexed
            val rule = catalog.rule(e.origin, e.ruleId)
            // Only accept files this device is a destination for, whoever is sending them.
            if (rule == null || rule.removed || id !in rule.destinations) return@forEachIndexed
            val meta = Incoming(e.hash, e.size, e.origin, nameOf(e.origin), e.ruleId, rule.label, e.relPath)

            val existing = store.entry(e.origin, e.ruleId, e.relPath)
            if (existing != null && existing.hash == e.hash && store.has(e.hash)) { refreshHold(e.hash); return@forEachIndexed }
            // Same content already kept under another name: copy it locally instead of downloading.
            if (store.has(e.hash)) { copyLocally(store, meta); return@forEachIndexed }

            val partial = store.partialLength(e.hash).takeIf { it in 1 until e.size } ?: 0L
            if (!store.canFit(bytesNeeded + e.size - partial)) { full = true; return@forEachIndexed }
            if (!store.claim(e.hash)) return@forEachIndexed
            bytesNeeded += e.size - partial
            wanted += Wanted(index, partial, meta)
        }
        if (full) notes += "${state.deviceName} is running out of space. Some files weren't backed up."
        return IncomingPlan(wanted, notes)
    }

    private fun copyLocally(store: KeptStore, meta: Incoming) {
        val (entry, stream) = store.openRead(meta.hash) ?: return
        stream.use { if (store.receive(meta, 0, entry.size, it) is ReceiveResult.Stored) refreshHold(meta.hash) }
    }

    internal fun receive(meta: Incoming, offset: Long, length: Long, input: InputStream): ReceiveResult {
        val store = kept ?: run { com.psphere.core.skipExactly(input, length); return ReceiveResult.Rejected("no backup location") }
        val result = store.receive(meta, offset, length, input)
        if (result is ReceiveResult.Stored) {
            refreshHold(meta.hash)
            val media = Media.of(meta.relPath)
            val showInLibrary = catalog.rule(meta.origin, meta.ruleId)?.showInLibrary == true
            if (media != Media.OTHER && showInLibrary && !previews.has(meta.hash)) makePreview(store.root, result.entry.path, media, meta.hash)
        }
        return result
    }

    internal fun releaseClaim(hash: String) { kept?.release(hash) }
    internal fun flushKept() { kept?.flush(); save() }

    /** Library items whose preview this device doesn't have yet. */
    internal fun previewsWanted(): List<String> =
        libraryItems(includeDeleted = true).map { it.hash }.distinct().filter { !previews.has(it) }

    internal fun onCatalogChanged() { onChange?.invoke() }

    // ── Sync: running it ─────────────────────────────────────────────────────

    /**
     * Syncs with a linked device. [addresses] come from discovery; the device's last known
     * address is tried after them.
     */
    fun syncWith(deviceId: String, addresses: List<HostPort> = emptyList(), onProgress: (SyncProgress) -> Unit = {}): SyncResult {
        val dev = device(deviceId) ?: return SyncResult(deviceId, deviceId, error = "Not a linked device")
        val lock = peerLocks.getOrPut(deviceId) { ReentrantLock() }
        if (!lock.tryLock()) return SyncResult(deviceId, dev.name, notes = listOf("Already syncing with ${dev.name}"))
        try {
            val (socket, address) = connectTo(dev, addresses) ?: return SyncResult(deviceId, dev.name, error = "Couldn't reach ${dev.name}")
            socket.use {
                val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                val session = Session(this, wire, dev, initiator = true, onProgress = onProgress)
                return try {
                    handshake(wire, dev)
                    noteAddress(deviceId, address)
                    socket.soTimeout = SESSION_TIMEOUT_MS
                    // Swap Shelves too, so a device that was away catches up on what was shared.
                    session.run().also { markSynced(deviceId); runCatching { shelfExchange(dev, listOf(address)) } }
                } catch (e: Exception) {
                    session.result(describe(e, dev.name))
                } finally {
                    flushKept()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun connectTo(dev: LinkedDevice, addresses: List<HostPort>): Pair<SSLSocket, HostPort>? {
        val candidates = (addresses + listOfNotNull(dev.lastHost?.let { HostPort(it, dev.lastPort) })).distinct()
        for (a in candidates) {
            val s = runCatching { tls.connect(a.host, a.port) }.getOrNull() ?: continue
            if (Tls.peerFingerprint(s) == dev.fingerprint) return s to a
            s.close() // Something else answered at that address (or a device that was reinstalled).
        }
        return null
    }

    /** Client side of the opening exchange with a linked device. */
    private fun handshake(wire: Wire, dev: LinkedDevice) {
        wire.send(Msg.Hello(Wire.VERSION, id, state.deviceName, serverPort))
        val hello = wire.expect<Msg.Hello>()
        if (hello.deviceId != dev.id) throw ProtocolException("${dev.name} identified itself incorrectly")
    }

    // ── Server side ──────────────────────────────────────────────────────────

    /** Handles one incoming connection, on the caller's thread. Closes the socket. */
    fun handle(raw: Socket) {
        raw.use {
            val ssl = runCatching { Tls.accept(raw) }.getOrNull() ?: return
            val fp = runCatching { Tls.peerFingerprint(ssl) }.getOrNull() ?: return
            val wire = Wire(ssl.inputStream, ssl.outputStream)
            runCatching {
                val hello = wire.expect<Msg.Hello>()
                if (hello.version != Wire.VERSION) {
                    wire.send(Msg.Error("${state.deviceName} has a different version of PSphere. Update PSphere on both devices."))
                    return
                }
                if (hello.deviceId != fp.deviceId) { wire.send(Msg.Error("Identity mismatch")); return }
                val dev = device(fp.deviceId)?.takeIf { it.fingerprint == fp }
                wire.send(Msg.Hello(Wire.VERSION, id, state.deviceName, serverPort))
                if (dev == null) { handlePairing(ssl, wire, fp, hello); return }
                // Remember where it can be reached, so this device can start syncs with it too.
                addressOf(ssl, hello)?.let { noteAddress(dev.id, it) }

                ssl.soTimeout = SESSION_TIMEOUT_MS
                when (val req = wire.receive()) {
                    is Msg.Vector -> serveSync(wire, dev, req.known)
                    is Msg.Get -> serveGet(wire, req)
                    is Msg.Has -> wire.send(Msg.HasReply(req.hashes.filter { isValidHash(it) && holdsLocally(it) }))
                    is Msg.Smart -> wire.send(askHere(req.op, req.args)?.let { Msg.SmartReply(true, it) } ?: Msg.SmartReply(false, emptyList()))
                    is Msg.Shelf -> {
                        mergeShelf(req.items, addressOf(ssl, hello)?.let { mapOf(dev.id to it) } ?: emptyMap())
                        wire.send(Msg.ShelfReply(shelf.all()))
                    }
                    else -> wire.send(Msg.Error("Unexpected request"))
                }
            }
        }
    }

    /**
     * Not limited by [peerLocks]: if both devices start a sync with each other at the same moment,
     * refusing here would make both fail. Running both is safe because downloads are claimed per file.
     */
    private fun serveSync(wire: Wire, dev: LinkedDevice, peerVector: Map<String, Long>) {
        // Pick up files added since this device last looked, so the other device gets them now.
        // Time-limited because it is waiting for a reply; skipped if a scan is already running.
        if (scanLock.tryLock()) {
            try { runCatching { scanLocked(SERVE_SCAN_BUDGET_MS) { _, _ -> } } } finally { scanLock.unlock() }
        }
        var result = SyncResult(dev.id, dev.name, error = "${dev.name} disconnected")
        try {
            result = Session(this, wire, dev, initiator = false, onProgress = { p -> onIncomingProgress?.invoke(p) }, firstPeerVector = peerVector).run()
            markSynced(dev.id)
        } finally {
            flushKept()
            onIncomingDone?.invoke(result)
            onChange?.invoke()
        }
    }

    private fun serveGet(wire: Wire, req: Msg.Get) {
        val src = if (isValidHash(req.hash)) openLocal(req.hash) else null
        if (src == null || req.offset < 0 || req.offset > src.size) {
            src?.close()
            wire.send(Msg.Error("That file isn't on ${state.deviceName}"))
            return
        }
        src.use {
            com.psphere.core.skipExactly(it.stream, req.offset)
            wire.send(Msg.FileData(0, req.offset, it.size - req.offset))
            copyExactly(it.stream, wire.output, it.size - req.offset)
            wire.flush()
        }
        runCatching { wire.expect<Msg.Bye>() }
    }

    // ── On-demand: fetch, download back, re-check ────────────────────────────

    /**
     * Streams the full file [hash] from the first reachable device in [online] that holds it.
     * [sink] receives the stream and its length and must consume exactly that many bytes.
     */
    fun fetch(hash: String, online: Map<String, HostPort>, sink: (InputStream, Long) -> Unit): Boolean {
        // Devices seen on the network first, then the rest at their last known address: right
        // after starting, or where discovery is blocked, the holder may not have been seen yet.
        val holders = catalog.holders(hash).filter { it != id && (it in online || device(it)?.lastHost != null) }
            .sortedBy { if (it in online) 0 else 1 }
        for (holder in holders) {
            val dev = device(holder) ?: continue
            val (socket, _) = connectTo(dev, listOfNotNull(online[holder])) ?: continue
            val ok = socket.use {
                runCatching {
                    val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                    handshake(wire, dev)
                    socket.soTimeout = SESSION_TIMEOUT_MS
                    wire.send(Msg.Get(hash, 0))
                    val fd = wire.expect<Msg.FileData>()
                    sink(wire.input, fd.length)
                    wire.send(Msg.Bye)
                    true
                }.getOrDefault(false)
            }
            if (ok) return true
        }
        return false
    }

    /** Full-size copy for viewing, cached in app storage. Returns null if no holder is reachable. */
    fun fetchToCache(hash: String, online: Map<String, HostPort>): File? {
        if (!isValidHash(hash)) return null
        val target = File(cacheDir, hash)
        if (target.isFile) return target
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$hash.part")
        val ok = fetch(hash, online) { input, len ->
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { copyExactly(input, it, len, digest) }
            if (digest.digest().toHex() != hash) throw IOException("checksum mismatch")
        }
        if (!ok || !tmp.renameTo(target)) { tmp.delete(); return null }
        return target
    }

    /**
     * A local file holding the full-size item, for viewing: the cached copy if present, otherwise
     * copied out of local storage (own or kept), otherwise fetched from a reachable holder.
     * Null if the item isn't here and no holder can be reached. Blocking; call off the main thread.
     */
    fun fullFile(item: ItemRec, online: Map<String, HostPort>): File? {
        if (!isValidHash(item.hash)) return null
        val target = File(cacheDir, item.hash)
        if (target.isFile && target.length() == item.size) return target
        if (!holdsLocally(item.hash)) return fetchToCache(item.hash, online)
        val src = openLocal(item.hash) ?: return fetchToCache(item.hash, online)
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${item.hash}.part")
        return try {
            src.use { s -> tmp.outputStream().use { s.stream.copyTo(it) } }
            if (tmp.renameTo(target)) target else { tmp.delete(); null }
        } catch (e: Exception) {
            tmp.delete(); null
        }
    }

    /**
     * Download to this device. Own items (e.g. freed ones) go back to their original folder;
     * other devices' items go into the backup location like a normal backup.
     */
    fun download(item: ItemRec, online: Map<String, HostPort>): Boolean {
        if (holdsLocally(item.hash) && (item.origin != id || item.state == ItemState.PRESENT)) return true
        val ok = if (item.origin == id) downloadOwn(item, online) else downloadKept(item, online)
        if (ok) { refreshHold(item.hash); save(); onChange?.invoke() }
        return ok
    }

    private fun downloadOwn(item: ItemRec, online: Map<String, HostPort>): Boolean {
        val folder = sourceFolder(item.ruleId) ?: return false
        val tmp = ".psphere-${item.hash.take(16)}.part"
        val got = fetch(item.hash, online) { input, len ->
            val digest = MessageDigest.getInstance("SHA-256")
            folder.openWrite(tmp).use { copyExactly(input, it, len, digest) }
            if (digest.digest().toHex() != item.hash) throw IOException("checksum mismatch")
        }
        if (!got) { folder.delete(tmp); return false }
        var target = item.relPath
        var n = 2
        while (folder.exists(target)) target = withSuffix(item.relPath, n++)
        if (!folder.move(tmp, target)) { folder.delete(tmp); return false }
        val info = folder.info(target)
        catalog.putOwn(item.copy(relPath = target, state = ItemState.PRESENT, modified = info?.modified ?: item.modified))
        if (target != item.relPath) catalog.putOwn(item.copy(state = ItemState.DELETED))
        return true
    }

    private fun downloadKept(item: ItemRec, online: Map<String, HostPort>): Boolean {
        val store = kept ?: return false
        val rule = catalog.rule(item.origin, item.ruleId)
        val meta = Incoming(item.hash, item.size, item.origin, nameOf(item.origin), item.ruleId, rule?.label ?: item.ruleId, item.relPath)
        if (!store.claim(item.hash)) return false
        try {
            var result: ReceiveResult? = null
            fetch(item.hash, online) { input, len -> result = store.receive(meta, 0, len, input) }
            store.flush()
            return result is ReceiveResult.Stored
        } finally {
            store.release(item.hash)
        }
    }

    /** Asks [deviceId] which of [hashes] it really has right now. Null if it can't be reached. */
    fun askHas(deviceId: String, address: HostPort, hashes: List<String>): Set<String>? {
        val dev = device(deviceId) ?: return null
        val (socket, _) = connectTo(dev, listOf(address)) ?: return null
        return socket.use {
            runCatching {
                val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                handshake(wire, dev)
                socket.soTimeout = SESSION_TIMEOUT_MS
                val result = HashSet<String>()
                hashes.chunked(20_000).forEach { chunk ->
                    wire.send(Msg.Has(chunk))
                    result += wire.expect<Msg.HasReply>().hashes
                }
                result
            }.getOrNull()
        }
    }

    // ── Free up space ────────────────────────────────────────────────────────

    /** Files in this device's backup folders that are safely stored on other devices. */
    fun planFreeUp(): FreeUpPlan {
        val linked = devices().map { it.id }.toSet()
        val items = ownRules().flatMap { (rule, local) ->
            val cutoff = if (local.keepRecentDays > 0) now() - local.keepRecentDays * 24L * 3600 * 1000 else Long.MAX_VALUE
            catalog.items(id).filter { it.ruleId == rule.ruleId && it.state == ItemState.PRESENT && it.takenAt < cutoff }
                .filter { safeElsewhere(it, rule, local, catalog.holders(it.hash) intersect linked) }
        }
        return FreeUpPlan(items, items.sumOf { it.size })
    }

    private fun safeElsewhere(item: ItemRec, rule: RuleRec, local: LocalRule, holders: Set<String>): Boolean {
        val others = holders - id
        if (others.isEmpty()) return false
        if (!local.freeNeedsAllDestinations) return true
        val dests = rule.destinations.filter { device(it) != null }
        return dests.isNotEmpty() && dests.all { it in others }
    }

    /**
     * Deletes the planned files from this device. Devices in [online] are asked again first; a
     * file is only deleted if it is still safe with their fresh answers.
     */
    fun freeUp(plan: FreeUpPlan, online: Map<String, HostPort>): FreeUpResult {
        val linked = devices().map { it.id }.toSet()
        val involved = plan.items.flatMap { catalog.holders(it.hash) }.toSet() - id
        val confirmed = HashMap<String, Set<String>?>()
        for (dev in involved) {
            val addr = online[dev] ?: continue
            confirmed[dev] = askHas(dev, addr, plan.items.filter { dev in catalog.holders(it.hash) }.map { it.hash }.distinct())
        }
        val notRechecked = involved.filter { confirmed[it] == null }.toSet()

        var freed = 0; var bytes = 0L
        val skipped = ArrayList<Pair<ItemRec, String>>()
        val rules = ownRules().associateBy { it.first.ruleId }
        for (item in plan.items) {
            val (rule, local) = rules[item.ruleId] ?: run { skipped += item to "folder no longer backed up"; null } ?: continue
            val holders = (catalog.holders(item.hash) intersect linked).filter { confirmed[it]?.contains(item.hash) ?: true }.toSet()
            if (!safeElsewhere(item, rule, local, holders)) { skipped += item to "not confirmed on another device"; continue }
            val folder = platform.openFolder(local.location) ?: run { skipped += item to "folder unavailable"; null } ?: continue
            val info = folder.info(item.relPath)
            if (info == null || info.size != item.size || info.modified != item.modified) { skipped += item to "changed since the last scan"; continue }
            if (Media.of(item.relPath) != Media.OTHER && !previews.has(item.hash)) makePreview(folder, item.relPath, item.media, item.hash)
            if (!folder.delete(item.relPath)) { skipped += item to "couldn't delete"; continue }
            catalog.putOwn(item.copy(state = ItemState.FREED))
            refreshHold(item.hash)
            freed++; bytes += item.size
        }
        save()
        onChange?.invoke()
        return FreeUpResult(freed, bytes, skipped, notRechecked)
    }

    // ── Library ──────────────────────────────────────────────────────────────

    private fun libraryItems(includeDeleted: Boolean): List<ItemRec> =
        catalog.items().filter { item ->
            val rule = catalog.rule(item.origin, item.ruleId)
            rule != null && rule.showInLibrary && !rule.removed && item.media != Media.OTHER &&
                (includeDeleted || item.state != ItemState.DELETED)
        }

    /**
     * The whole library across linked devices, newest first. Items that no device could provide are
     * left out: not here, and the only copies are on devices this one isn't linked with (for example
     * leftovers from a device unlinked by an older version), since they could never be opened.
     */
    fun library(includeDeleted: Boolean = false): List<LibraryItem> {
        val linked = synchronized(this) { state.devices.keys.toSet() }
        val version = catalog.version
        // Screens and smart photos ask often; rebuild only when the catalog or the links changed.
        libraryCache[includeDeleted]?.let { c -> if (c.version == version && c.linked == linked) return c.items }
        return buildLibrary(includeDeleted, linked).also { libraryCache[includeDeleted] = LibrarySnapshot(version, linked, it) }
    }

    private class LibrarySnapshot(val version: Long, val linked: Set<String>, val items: List<LibraryItem>)
    private val libraryCache = ConcurrentHashMap<Boolean, LibrarySnapshot>()

    private fun buildLibrary(includeDeleted: Boolean, linked: Set<String>): List<LibraryItem> {
        return libraryItems(includeDeleted).sortedByDescending { it.takenAt }.mapNotNull { item ->
            val local = (item.origin == id && item.state == ItemState.PRESENT) || kept?.has(item.hash) == true
            val holders = catalog.holders(item.hash)
            val reachable = local || holders.any { it in linked } ||
                (item.origin in linked && item.state == ItemState.PRESENT)
            if (reachable) LibraryItem(item, nameOf(item.origin), local, holders) else null
        }
    }

    // ── Smart photos ─────────────────────────────────────────────────────────

    private fun askHere(op: String, args: List<String>): List<SmartItem>? =
        runCatching { smart?.ask(op, args.map { it.trim().take(Wire.MAX_QUERY) }) }.getOrNull()
            ?.filter { it.kind != SmartItem.Kind.PHOTO || isValidHash(it.id) }

    /**
     * Asks the smart features (see [SmartOps]): this device's own if it has them, otherwise every
     * linked device at once, at its address in [online] or its last known one (discovery is
     * sometimes blocked). The first device with smart features answers. When none can be asked,
     * the last answer kept on this device is used, so People and Places work away from home. Blocking.
     */
    fun smartAsk(op: String, args: List<String> = emptyList(), online: Map<String, HostPort>): SmartAnswer {
        askHere(op, args)?.let { return SmartAnswer(it, listOf(state.deviceName)) }
        val targets = devices().filter { it.id in online || it.lastHost != null }
        var answer: SmartAnswer? = null
        var answeredBy = ""
        if (targets.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(targets.size.coerceAtMost(8)) { r ->
                Thread(r, "psphere-smart").apply { isDaemon = true }
            }
            try {
                val service = java.util.concurrent.ExecutorCompletionService<Pair<LinkedDevice, List<SmartItem>?>>(pool)
                targets.forEach { dev -> service.submit { dev to askDevice(dev, listOfNotNull(online[dev.id]), op, args) } }
                val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
                for (n in targets.indices) {
                    val wait = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                    val (dev, items) = service.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS)?.get() ?: break
                    if (items != null) { answer = SmartAnswer(items, listOf(dev.name)); answeredBy = dev.id; break }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        answer?.let { if (op in SmartOps.CACHED) smartCache.put(op, args, answeredBy, it.items); return it }
        val linked = synchronized(this) { state.devices.keys.toSet() }
        smartCache.get(op, args, linked)?.let { return SmartAnswer(it, emptyList(), saved = true) }
        return SmartAnswer(emptyList(), emptyList())
    }

    /** Library photos for the PHOTO rows of a smart answer, in the same order. */
    fun photosOf(items: List<SmartItem>): List<LibraryItem> {
        val byHash = library().groupBy { it.item.hash }
        return items.filter { it.kind == SmartItem.Kind.PHOTO }.mapNotNull { byHash[it.id]?.first() }
    }

    /** "1 year ago today" and so on, newest year first. */
    fun memories(): List<Memory> = memoriesOf(library(), now())

    /** Null if the device can't be reached or has no smart features (including older versions). */
    private fun askDevice(dev: LinkedDevice, addresses: List<HostPort>, op: String, args: List<String>): List<SmartItem>? {
        val (socket, _) = connectTo(dev, addresses) ?: return null
        return socket.use {
            runCatching {
                val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                handshake(wire, dev)
                socket.soTimeout = SEARCH_TIMEOUT_MS.toInt()
                wire.send(Msg.Smart(op, args))
                val reply = wire.expect<Msg.SmartReply>()
                if (reply.ready) reply.items.filter { it.kind != SmartItem.Kind.PHOTO || isValidHash(it.id) } else null
            }.getOrNull()
        }
    }

    // ── Shelf ────────────────────────────────────────────────────────────────

    /** What's on the Shelf: pinned first, then newest first. */
    fun shelfItems(): List<ShelfEntry> = shelf.live().map(::shelfEntry)

    private fun shelfEntry(item: ShelfItem) = ShelfEntry(item, nameOf(item.origin), !item.isFile || shelf.hasFile(item.hash))

    /** Puts text or a link on the Shelf. Very long text goes on as a .txt file. Null if it's blank. */
    fun shelfAddText(text: String): ShelfItem? {
        val clean = text.trim().ifEmpty { return null }
        val item = if (clean.toByteArray(Charsets.UTF_8).size > ShelfStore.MAX_TEXT_BYTES) {
            shelf.addFile(id, "Text ${fileStamp()}.txt", clean.byteInputStream(Charsets.UTF_8))
        } else {
            shelf.addText(id, clean)
        }
        onChange?.invoke()
        return item
    }

    /** Copies a file onto the Shelf. Blocking for big files. */
    fun shelfAddFile(name: String, input: InputStream): ShelfItem = shelf.addFile(id, name, input).also { onChange?.invoke() }

    fun shelfPin(itemId: String, pinned: Boolean) { if (shelf.setPinned(itemId, pinned) != null) onChange?.invoke() }

    /** Takes items off the Shelf on every device. */
    fun shelfRemove(ids: Collection<String>) { if (shelf.remove(ids) > 0) onChange?.invoke() }

    /** Unpinned items more than a week old. They stay until the user clears them. */
    fun shelfOld(): List<ShelfItem> = shelf.old()

    /** True when the Shelf should suggest clearing old items. */
    fun shelfShouldAskAboutOld(): Boolean = shelf.shouldAskAboutOld()

    /** The user chose to keep the old items: don't ask again for a week. */
    fun shelfKeepOld() { shelf.keepOld(); onChange?.invoke() }

    /**
     * A Shelf file on this device, fetched from another device first if it isn't here: the device
     * that put it there, then any other. Null if no device that has it can be reached. Blocking.
     */
    fun shelfFile(item: ShelfItem, online: Map<String, HostPort> = emptyMap()): File? {
        if (!item.isFile || !isValidHash(item.hash)) return null
        shelf.file(item.hash)?.let { return it }
        synchronized(shelfLocks.getOrPut(item.hash) { Any() }) {
            shelf.file(item.hash)?.let { return it }
            val sources = (listOf(item.origin) + devices().map { it.id }).distinct()
                .mapNotNull { if (it == id) null else device(it) }
                .filter { it.id in online || it.lastHost != null }
                .sortedBy { if (it.id in online) 0 else 1 }
            for (dev in sources) {
                val (socket, _) = connectTo(dev, listOfNotNull(online[dev.id])) ?: continue
                val file = socket.use {
                    runCatching {
                        val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                        handshake(wire, dev)
                        socket.soTimeout = SESSION_TIMEOUT_MS
                        wire.send(Msg.Get(item.hash, 0))
                        val fd = wire.expect<Msg.FileData>()
                        if (fd.length != item.size) throw IOException("size mismatch")
                        shelf.storeFetched(item.hash) { out -> copyExactly(wire.input, out, fd.length) }
                            ?.also { runCatching { wire.send(Msg.Bye) } }
                    }.getOrNull()
                }
                if (file != null) { onChange?.invoke(); return file }
            }
            return null
        }
    }

    /**
     * Swaps Shelves with every linked device that can be reached, at its address in [online] or
     * its last known one. Call after a change so the other devices see it now. Returns how many
     * devices answered (older versions don't). Blocking.
     */
    fun shelfSync(online: Map<String, HostPort> = emptyMap()): Int {
        val targets = devices().filter { it.id in online || it.lastHost != null }
        if (targets.isEmpty()) return 0
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(8)) { r -> Thread(r, "psphere-shelf-sync").apply { isDaemon = true } }
        try {
            val jobs = targets.map { dev -> pool.submit<Boolean> { shelfExchange(dev, listOfNotNull(online[dev.id])) } }
            return jobs.count { runCatching { it.get(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** One swap with [dev]: send this Shelf, merge the one it sends back. False if it couldn't be done. */
    private fun shelfExchange(dev: LinkedDevice, addresses: List<HostPort>): Boolean {
        val (socket, address) = connectTo(dev, addresses) ?: return false
        val theirs = socket.use {
            runCatching {
                val wire = Wire(socket.inputStream, socket.outputStream) { dev.name }
                handshake(wire, dev)
                socket.soTimeout = SEARCH_TIMEOUT_MS.toInt()
                wire.send(Msg.Shelf(shelf.all()))
                wire.expect<Msg.ShelfReply>().items
            }.getOrNull()
        } ?: return false
        noteAddress(dev.id, address)
        mergeShelf(theirs, mapOf(dev.id to address))
        return true
    }

    private fun mergeShelf(items: List<ShelfItem>, online: Map<String, HostPort>) {
        val accepted = synchronized(this) { state.devices.keys + id }
        val result = shelf.merge(items, accepted)
        if (!result.changed) return
        onChange?.invoke()
        val fromOthers = result.added.filter { it.origin != id }
        if (fromOthers.isNotEmpty()) onShelfAdded?.invoke(fromOthers.map(::shelfEntry))
        // Small files come over straight away, so they're ready when the user looks.
        shelf.live().filter { it.isFile && it.size <= ShelfStore.AUTO_FETCH_BYTES && !shelf.hasFile(it.hash) }.forEach { item ->
            runCatching { shelfWorker.execute { runCatching { shelfFile(item, online) } } }
        }
    }

    private fun fileStamp(): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")
            .format(java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()))

    // ── Linking ──────────────────────────────────────────────────────────────

    /** Opens a 10-minute window for another device to link, and returns what the QR code shows. */
    fun openPairing(hosts: List<String>, port: Int, allowCode: Boolean = true): PairingOffer {
        val token = PairingOffer.newToken()
        pairing = PairingWindow(token, now() + PAIRING_WINDOW_MS, allowCode)
        return PairingOffer(id, state.deviceName, hosts, port, identity.fingerprint, token)
    }

    fun closePairing() { pairing = null }

    val isPairingOpen: Boolean get() = pairing?.let { now() < it.expiresAt } == true

    /** Links with the device whose QR code was scanned. */
    fun linkWithQr(uri: String): LinkedDevice {
        val offer = PairingOffer.parse(uri) ?: throw ProtocolException("That isn't a PSphere link code")
        var lastError: Exception? = null
        for (host in offer.hosts) {
            val socket = runCatching { tls.connect(host, offer.port) }.getOrElse { lastError = it as? Exception; null } ?: continue
            socket.use {
                if (Tls.peerFingerprint(socket) != offer.fingerprint) {
                    throw ProtocolException("The device at that address isn't the one in the QR code")
                }
                val wire = Wire(socket.inputStream, socket.outputStream) { offer.name }
                wire.send(Msg.Hello(Wire.VERSION, id, state.deviceName, serverPort))
                wire.expect<Msg.Hello>()
                wire.send(Msg.PairRequest(offer.token, codeMode = false))
                val ok = wire.expect<Msg.PairOk>()
                val device = link(offer.fingerprint, ok.name, HostPort(host, offer.port))
                // Tell the other device we've saved the link, so it saves its side too (not before).
                runCatching { wire.send(Msg.PairDone) }
                return device
            }
        }
        throw IOException("Couldn't reach ${offer.name}. Make sure both devices are on the same Wi-Fi.", lastError)
    }

    /** Links with a device found on the network, by comparing a 6-digit code on both screens. */
    fun linkWithCode(address: HostPort, confirmer: CodeConfirmer): LinkedDevice {
        tls.connect(address.host, address.port).use { socket ->
            val fp = Tls.peerFingerprint(socket)
            socket.soTimeout = PAIRING_TIMEOUT_MS
            val wire = Wire(socket.inputStream, socket.outputStream)
            wire.send(Msg.Hello(Wire.VERSION, id, state.deviceName, serverPort))
            val hello = wire.expect<Msg.Hello>()
            if (hello.deviceId != fp.deviceId) throw ProtocolException("The other device identified itself incorrectly")
            wire.send(Msg.PairRequest("", codeMode = true))
            val accepted = confirmer.confirm(hello.name, PairingOffer.shortCode(identity.fingerprint, fp))
            wire.send(Msg.PairConfirm(accepted))
            val ok = wire.expect<Msg.PairOk>()
            if (!accepted) throw ProtocolException("Linking was cancelled")
            val device = link(fp, ok.name, address)
            // Tell the other device we've saved the link, so it saves its side too (not before).
            runCatching { wire.send(Msg.PairDone) }
            return device
        }
    }

    /** The other device's address: where it connected from, at the port it listens on (newer versions say). */
    private fun addressOf(socket: Socket, hello: Msg.Hello): HostPort? {
        val ip = socket.inetAddress?.hostAddress ?: return null
        return if (hello.port in 1..65535) HostPort(ip, hello.port) else null
    }

    private fun handlePairing(socket: SSLSocket, wire: Wire, fp: Fingerprint, hello: Msg.Hello) {
        socket.soTimeout = PAIRING_TIMEOUT_MS
        val req = wire.expect<Msg.PairRequest>()
        val window = pairing?.takeIf { now() < it.expiresAt }
        if (window == null) { wire.send(Msg.Error("${state.deviceName} isn't ready to link. Open “Link a device” on it first.")); return }

        if (!req.codeMode) {
            if (!MessageDigest.isEqual(req.token.toByteArray(), window.token.toByteArray())) {
                wire.send(Msg.Error("That link code has expired. Show a new one."))
                return
            }
            pairing = null
            // Accept, then wait for the initiator to confirm it has saved the link before saving
            // ours — a dropped connection must not leave a one-sided link.
            wire.send(Msg.PairOk(state.deviceName))
            wire.expect<Msg.PairDone>()
            link(fp, hello.name, addressOf(socket, hello))
            onChange?.invoke()
            return
        }

        val confirmer = codeConfirmer
        if (!window.allowCode || confirmer == null) { wire.send(Msg.Error("Scan the QR code on ${state.deviceName} instead.")); return }
        val mine = confirmer.confirm(hello.name, PairingOffer.shortCode(identity.fingerprint, fp))
        val theirs = wire.expect<Msg.PairConfirm>().accepted
        if (!mine || !theirs) { wire.send(Msg.Error("Linking was cancelled")); return }
        pairing = null
        // Save the link only after the initiator confirms it has saved its side (see the QR branch),
        // so a lost connection can't leave this device linked on its own.
        wire.send(Msg.PairOk(state.deviceName))
        wire.expect<Msg.PairDone>()
        link(fp, hello.name, addressOf(socket, hello))
        onChange?.invoke()
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Synchronized
    fun save() {
        state.save(stateFile)
        catalog.save(catalogFile)
    }

    private fun withSuffix(path: String, n: Int): String {
        val dot = path.lastIndexOf('.')
        return if (dot > path.lastIndexOf('/')) "${path.substring(0, dot)} ($n)${path.substring(dot)}" else "$path ($n)"
    }

    private fun describe(e: Exception, peer: String): String = when (e) {
        is Wire.PeerError -> e.message ?: "$peer reported an error"
        is ProtocolException -> e.message ?: "Unexpected reply from $peer"
        is java.net.SocketTimeoutException -> "$peer stopped responding"
        is java.io.EOFException, is java.net.SocketException -> "The connection to $peer was lost"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val SESSION_TIMEOUT_MS = 60_000
        /** Well under [SESSION_TIMEOUT_MS], which the other device waits for the first reply. */
        const val SERVE_SCAN_BUDGET_MS = 20_000L
        const val PAIRING_TIMEOUT_MS = 180_000
        const val PAIRING_WINDOW_MS = 10 * 60_000L
        const val SEARCH_TIMEOUT_MS = 15_000L
    }
}
