package com.psphere.desktop.smart

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

/**
 * What smart search knows about each photo: one vector per photo hash, stored as 8-bit numbers
 * (about half a kilobyte each). Search compares the query's vector with every photo's, which
 * takes a few milliseconds even for tens of thousands of photos.
 */
class SmartIndex(private val file: File) {
    private class Entry(val scale: Float, val v: ByteArray)
    private val entries = HashMap<String, Entry>()
    private var dirty = 0

    init { load() }

    @Synchronized fun size() = entries.size
    @Synchronized fun has(hash: String) = hash in entries
    @Synchronized fun hashes(): Set<String> = entries.keys.toSet()

    @Synchronized
    fun put(hash: String, vector: FloatArray) {
        val maxAbs = vector.maxOf { abs(it) }.takeIf { it > 0 } ?: return
        val scale = maxAbs / 127f
        entries[hash] = Entry(scale, ByteArray(vector.size) { (vector[it] / scale).toInt().coerceIn(-127, 127).toByte() })
        if (++dirty >= 50) save()
    }

    /** Forgets photos that are no longer in the library. */
    @Synchronized
    fun retain(keep: Set<String>) {
        if (entries.keys.retainAll(keep)) save()
    }

    /** Photos whose similarity to [query] is at least [minScore], best first. */
    @Synchronized
    fun search(query: FloatArray, limit: Int, minScore: Float): List<Pair<String, Float>> {
        val scored = ArrayList<Pair<String, Float>>()
        for ((hash, e) in entries) {
            if (e.v.size != query.size) continue
            var s = 0f
            for (i in query.indices) s += query[i] * e.v[i]
            s *= e.scale
            if (s >= minScore) scored += hash to s
        }
        scored.sortByDescending { it.second }
        return if (scored.size > limit) scored.subList(0, limit).toList() else scored
    }

    @Synchronized
    fun clear() { entries.clear(); file.delete(); dirty = 0 }

    @Synchronized
    fun save() {
        dirty = 0
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(SmartModels.MODEL_ID)
            out.writeInt(entries.size)
            for ((hash, e) in entries) {
                out.writeUTF(hash); out.writeFloat(e.scale); out.writeShort(e.v.size); out.write(e.v)
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != FORMAT || input.readUTF() != SmartModels.MODEL_ID) return
                repeat(input.readInt()) {
                    val hash = input.readUTF()
                    val scale = input.readFloat()
                    val v = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    entries[hash] = Entry(scale, v)
                }
            }
        }.onFailure { entries.clear() } // damaged: photos are simply scanned again
    }

    companion object { private const val FORMAT = 1 }
}
