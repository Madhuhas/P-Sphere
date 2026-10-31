package com.psphere.core.catalog

import com.psphere.core.model.DeviceRec
import com.psphere.core.model.HoldRec
import com.psphere.core.model.ItemRec
import com.psphere.core.model.Rec
import com.psphere.core.model.RuleRec
import com.psphere.core.net.Codec.rec
import com.psphere.core.net.Codec.str
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The replicated catalog: device names, backup folders (rules), items and who holds which file.
 *
 * Replication: each device sends the other its [vector] (highest seq seen per origin) and gets
 * back every record it is missing, ordered by (origin, seq). Applying them in that order means an
 * interrupted transfer simply resumes from where it stopped next time. See [Rec].
 */
class Catalog(val selfId: String) {
    private val records = HashMap<String, Rec>()
    private val known = HashMap<String, Long>()
    private val holders = HashMap<String, MutableSet<String>>()
    private val itemsByHash = HashMap<String, MutableSet<String>>()

    private fun id(origin: String, key: String) = origin + '\u0000' + key

    // ── Writing ──────────────────────────────────────────────────────────────

    /** Records a change made by this device. Returns false if nothing actually changed. */
    @Synchronized
    fun putOwn(r: Rec): Boolean {
        require(r.origin == selfId) { "Only this device's own records can be written" }
        val existing = records[id(r.origin, r.key)]
        if (existing != null && existing.withSeq(0) == r.withSeq(0)) return false
        store(r.withSeq((known[selfId] ?: 0) + 1))
        return true
    }

    /** Applies a record received from another device. Returns true if it was new. */
    @Synchronized
    fun apply(r: Rec): Boolean {
        val existing = records[id(r.origin, r.key)]
        if (existing != null && existing.seq >= r.seq) return false
        // Our own records only come back to us if this device's data was lost; take them back then.
        store(r)
        return true
    }

    private fun store(r: Rec) {
        val k = id(r.origin, r.key)
        unindex(records.put(k, r))
        index(r)
        if (r.seq > (known[r.origin] ?: 0)) known[r.origin] = r.seq
    }

    private fun index(r: Rec) {
        when (r) {
            is HoldRec -> if (r.present) holders.getOrPut(r.hash) { HashSet() } += r.origin
            is ItemRec -> itemsByHash.getOrPut(r.hash) { HashSet() } += id(r.origin, r.key)
            else -> Unit
        }
    }

    private fun unindex(r: Rec?) {
        when (r) {
            is HoldRec -> holders[r.hash]?.let { it -= r.origin; if (it.isEmpty()) holders -= r.hash }
            is ItemRec -> itemsByHash[r.hash]?.let { it -= id(r.origin, r.key); if (it.isEmpty()) itemsByHash -= r.hash }
            else -> Unit
        }
    }

    // ── Replication ──────────────────────────────────────────────────────────

    @Synchronized fun vector(): Map<String, Long> = HashMap(known)

    @Synchronized
    fun delta(peerKnown: Map<String, Long>): List<Rec> =
        records.values.filter { it.seq > (peerKnown[it.origin] ?: 0) }
            .sortedWith(compareBy<Rec>({ it.origin }, { it.seq }))

    // ── Queries ──────────────────────────────────────────────────────────────

    @Synchronized fun deviceName(origin: String): String? = (records[id(origin, "d")] as? DeviceRec)?.name

    @Synchronized fun origins(): Set<String> = records.values.mapTo(HashSet()) { it.origin }

    @Synchronized fun rule(origin: String, ruleId: String): RuleRec? = records[id(origin, "r:$ruleId")] as? RuleRec

    @Synchronized fun rules(origin: String? = null): List<RuleRec> =
        records.values.filterIsInstance<RuleRec>().filter { origin == null || it.origin == origin }

    @Synchronized fun item(origin: String, ruleId: String, relPath: String): ItemRec? =
        records[id(origin, ItemRec.itemKey(ruleId, relPath))] as? ItemRec

    @Synchronized fun items(origin: String? = null): List<ItemRec> =
        records.values.filterIsInstance<ItemRec>().filter { origin == null || it.origin == origin }

    @Synchronized fun itemsWithHash(hash: String): List<ItemRec> =
        itemsByHash[hash].orEmpty().mapNotNull { records[it] as? ItemRec }

    /** Devices that report holding a full copy of [hash]. */
    @Synchronized fun holders(hash: String): Set<String> = holders[hash]?.toSet().orEmpty()

    @Synchronized fun size() = records.size

    // ── Persistence ──────────────────────────────────────────────────────────

    @Synchronized
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(FORMAT)
            out.str(selfId)
            out.writeInt(records.size)
            // Ordered so a partly written file still loads consistently.
            records.values.sortedWith(compareBy<Rec>({ it.origin }, { it.seq })).forEach { out.rec(it) }
        }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) error("Couldn't save ${file.name}") }
    }

    companion object {
        private const val FORMAT = 1

        fun load(file: File, selfId: String): Catalog {
            val cat = Catalog(selfId)
            if (!file.isFile) return cat
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == FORMAT) { "Unknown catalog format" }
                require(input.str() == selfId) { "Catalog belongs to another device" }
                repeat(input.readInt()) { cat.apply(input.rec()) }
            }
            return cat
        }
    }
}
