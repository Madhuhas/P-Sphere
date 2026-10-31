package com.psphere.core.identity

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Minimal DER encoder: just enough to build a self-signed X.509 certificate. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, len: Int) {
        when {
            len < 0x80 -> out.write(len)
            len <= 0xff -> { out.write(0x81); out.write(len) }
            len <= 0xffff -> { out.write(0x82); out.write(len ushr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len ushr 16); out.write((len ushr 8) and 0xff); out.write(len and 0xff) }
        }
    }

    fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    fun explicit(tagNumber: Int, content: ByteArray) = tlv(0xa0 or tagNumber, content)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = ArrayList<Int>()
            var v = arc
            do { groups += (v and 0x7f).toInt(); v = v ushr 7 } while (v > 0)
            for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
