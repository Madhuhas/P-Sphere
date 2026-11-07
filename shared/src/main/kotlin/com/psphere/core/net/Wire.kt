package com.psphere.core.net

import com.psphere.core.ProtocolException
import com.psphere.core.model.Rec
import com.psphere.core.net.Codec.bytes
import com.psphere.core.net.Codec.rec
import com.psphere.core.net.Codec.str
import com.psphere.core.net.Codec.strList
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file one device offers to send to another. */
data class OfferEntry(
    val hash: String,
    val size: Long,
    val origin: String,
    val ruleId: String,
    val relPath: String,
)

/**
 * Protocol v3 messages. Each is sent as a frame: type (1 byte), payload length (4 bytes), payload.
 * File contents follow a [FileData] frame as exactly [FileData.length] raw bytes.
 */
sealed class Msg {
    data class Hello(val version: Int, val deviceId: String, val name: String) : Msg()
    data class Vector(val known: Map<String, Long>) : Msg()
    data class Records(val records: List<Rec>) : Msg()
    data class PreviewWant(val hashes: List<String>) : Msg()
    data class Preview(val hash: String, val data: ByteArray) : Msg()
    data class Offer(val entries: List<OfferEntry>) : Msg()
    /**
     * Indexes into the last [Offer], each with the byte offset to start from (non-zero to resume).
     * [notes] explain offered files the receiver turned down, e.g. "Laptop is full".
     */
    data class Want(val wanted: List<Pair<Int, Long>>, val notes: List<String> = emptyList()) : Msg()
    /** Followed by [length] raw bytes. A [length] of -1 means the sender couldn't read the file. */
    data class FileData(val index: Int, val offset: Long, val length: Long) : Msg()
    data class Ack(val index: Int, val ok: Boolean, val reason: String) : Msg()
    data class Get(val hash: String, val offset: Long) : Msg()
    data class Has(val hashes: List<String>) : Msg()
    data class HasReply(val hashes: List<String>) : Msg()
    data class PairRequest(val token: String, val codeMode: Boolean) : Msg()
    data class PairConfirm(val accepted: Boolean) : Msg()
    data class PairOk(val name: String) : Msg()
    /** Initiator → responder, sent only after the initiator has saved the link, so the responder
     * never records a one-sided link when the initiator has already given up. */
    object PairDone : Msg() { override fun toString() = "PairDone" }
    data class Error(val message: String) : Msg()
    object End : Msg() { override fun toString() = "End" }
    object Bye : Msg() { override fun toString() = "Bye" }

    val typeName: String get() = this::class.simpleName ?: "?"
}

class Wire(rawIn: InputStream, rawOut: OutputStream, private val peerLabel: () -> String = { "the other device" }) {
    val input = DataInputStream(BufferedInputStream(rawIn, 64 * 1024))
    val output = DataOutputStream(BufferedOutputStream(rawOut, 64 * 1024))

    fun send(msg: Msg) {
        val buf = ByteArrayOutputStream()
        val type = DataOutputStream(buf).use { encode(it, msg) }
        output.writeByte(type)
        output.writeInt(buf.size())
        buf.writeTo(output)
        output.flush()
    }

    /** Reads the next message. An [Msg.Error] from the peer is thrown as an IOException. */
    fun receive(): Msg {
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) throw ProtocolException("Message from ${peerLabel()} is too large")
        val payload = ByteArray(len).also { input.readFully(it) }
        val msg = DataInputStream(ByteArrayInputStream(payload)).use { decode(type, it) }
        if (msg is Msg.Error) throw PeerError(msg.message)
        return msg
    }

    inline fun <reified T : Msg> expect(): T {
        val m = receive()
        return m as? T ?: throw ProtocolException("Expected ${T::class.simpleName} but got ${m.typeName}")
    }

    fun flush() = output.flush()

    /** An error the other device reported. */
    class PeerError(message: String) : IOException(message)

    private fun encode(o: DataOutputStream, m: Msg): Int = when (m) {
        is Msg.Hello -> { o.writeInt(m.version); o.str(m.deviceId); o.str(m.name); 1 }
        is Msg.Vector -> { o.writeInt(m.known.size); m.known.forEach { (k, v) -> o.str(k); o.writeLong(v) }; 2 }
        is Msg.Records -> { o.writeInt(m.records.size); m.records.forEach { o.rec(it) }; 3 }
        is Msg.PreviewWant -> { o.strList(m.hashes); 4 }
        is Msg.Preview -> { o.str(m.hash); o.bytes(m.data); 5 }
        is Msg.Offer -> {
            o.writeInt(m.entries.size)
            m.entries.forEach { o.str(it.hash); o.writeLong(it.size); o.str(it.origin); o.str(it.ruleId); o.str(it.relPath) }
            6
        }
        is Msg.Want -> { o.writeInt(m.wanted.size); m.wanted.forEach { (i, off) -> o.writeInt(i); o.writeLong(off) }; o.strList(m.notes); 7 }
        is Msg.FileData -> { o.writeInt(m.index); o.writeLong(m.offset); o.writeLong(m.length); 8 }
        is Msg.Ack -> { o.writeInt(m.index); o.writeBoolean(m.ok); o.str(m.reason); 9 }
        is Msg.Get -> { o.str(m.hash); o.writeLong(m.offset); 10 }
        is Msg.Has -> { o.strList(m.hashes); 11 }
        is Msg.HasReply -> { o.strList(m.hashes); 12 }
        is Msg.PairRequest -> { o.str(m.token); o.writeBoolean(m.codeMode); 13 }
        is Msg.PairConfirm -> { o.writeBoolean(m.accepted); 14 }
        is Msg.PairOk -> { o.str(m.name); 15 }
        is Msg.Error -> { o.str(m.message.take(500)); 16 }
        Msg.End -> 17
        Msg.Bye -> 18
        Msg.PairDone -> 19
    }

    private fun decode(type: Int, i: DataInputStream): Msg = when (type) {
        1 -> Msg.Hello(i.readInt(), i.str(), i.str())
        2 -> Msg.Vector(HashMap<String, Long>().apply { repeat(count(i)) { put(i.str(), i.readLong()) } })
        3 -> Msg.Records(List(count(i)) { i.rec() })
        4 -> Msg.PreviewWant(i.strList())
        5 -> Msg.Preview(i.str(), i.bytes(MAX_PREVIEW))
        6 -> Msg.Offer(List(count(i)) { OfferEntry(i.str(), i.readLong(), i.str(), i.str(), i.str()) })
        7 -> Msg.Want(List(count(i)) { i.readInt() to i.readLong() }, i.strList(100))
        8 -> Msg.FileData(i.readInt(), i.readLong(), i.readLong())
        9 -> Msg.Ack(i.readInt(), i.readBoolean(), i.str())
        10 -> Msg.Get(i.str(), i.readLong())
        11 -> Msg.Has(i.strList())
        12 -> Msg.HasReply(i.strList())
        13 -> Msg.PairRequest(i.str(), i.readBoolean())
        14 -> Msg.PairConfirm(i.readBoolean())
        15 -> Msg.PairOk(i.str())
        16 -> Msg.Error(i.str())
        17 -> Msg.End
        18 -> Msg.Bye
        19 -> Msg.PairDone
        else -> throw ProtocolException("Unknown message type $type from ${peerLabel()}")
    }

    private fun count(i: DataInputStream): Int {
        val n = i.readInt()
        if (n < 0 || n > 10_000_000) throw ProtocolException("Invalid list length ($n)")
        return n
    }

    companion object {
        const val VERSION = 4
        const val MAX_FRAME = 64 * 1024 * 1024
        const val MAX_PREVIEW = 2 * 1024 * 1024
    }
}
