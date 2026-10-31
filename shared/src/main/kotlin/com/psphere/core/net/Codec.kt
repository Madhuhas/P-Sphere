package com.psphere.core.net

import com.psphere.core.ProtocolException
import com.psphere.core.model.DeviceRec
import com.psphere.core.model.HoldRec
import com.psphere.core.model.Include
import com.psphere.core.model.ItemRec
import com.psphere.core.model.ItemState
import com.psphere.core.model.Media
import com.psphere.core.model.Rec
import com.psphere.core.model.RuleRec
import java.io.DataInput
import java.io.DataOutput

/** Length-prefixed binary encoding shared by the wire protocol and the files on disk. */
internal object Codec {
    private const val MAX_STRING = 64 * 1024

    fun DataOutput.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    fun DataInput.str(): String {
        val n = readInt()
        if (n < 0 || n > MAX_STRING) throw ProtocolException("Invalid text length ($n)")
        return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
    }

    fun DataOutput.bytes(b: ByteArray) { writeInt(b.size); write(b) }

    fun DataInput.bytes(max: Int): ByteArray {
        val n = readInt()
        if (n < 0 || n > max) throw ProtocolException("Invalid data length ($n)")
        return ByteArray(n).also { readFully(it) }
    }

    fun DataOutput.strList(list: Collection<String>) { writeInt(list.size); list.forEach { str(it) } }

    fun DataInput.strList(max: Int = 1_000_000): List<String> {
        val n = readInt()
        if (n < 0 || n > max) throw ProtocolException("Invalid list length ($n)")
        return List(n) { str() }
    }

    inline fun <reified E : Enum<E>> DataInput.enum(): E {
        val i = readByte().toInt()
        return enumValues<E>().getOrNull(i) ?: throw ProtocolException("Unknown ${E::class.simpleName} ($i)")
    }

    fun DataOutput.enum(e: Enum<*>) = writeByte(e.ordinal)

    fun DataOutput.rec(r: Rec) {
        when (r) {
            is DeviceRec -> { writeByte(1); str(r.origin); writeLong(r.seq); str(r.name) }
            is RuleRec -> {
                writeByte(2); str(r.origin); writeLong(r.seq)
                str(r.ruleId); str(r.label); strList(r.destinations); enum(r.include)
                writeBoolean(r.showInLibrary); writeBoolean(r.removed)
            }
            is ItemRec -> {
                writeByte(3); str(r.origin); writeLong(r.seq)
                str(r.ruleId); str(r.relPath); str(r.hash); writeLong(r.size); writeLong(r.modified)
                writeLong(r.takenAt); enum(r.media); enum(r.state)
            }
            is HoldRec -> { writeByte(4); str(r.origin); writeLong(r.seq); str(r.hash); writeBoolean(r.present) }
        }
    }

    fun DataInput.rec(): Rec = when (val kind = readByte().toInt()) {
        1 -> DeviceRec(str(), readLong(), str())
        2 -> RuleRec(str(), readLong(), str(), str(), strList(64).toSet(), enum<Include>(), readBoolean(), readBoolean())
        3 -> ItemRec(str(), readLong(), str(), str(), str(), readLong(), readLong(), readLong(), enum<Media>(), enum<ItemState>())
        4 -> HoldRec(str(), readLong(), str(), readBoolean())
        else -> throw ProtocolException("Unknown record type ($kind)")
    }
}
