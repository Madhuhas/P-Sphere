package com.psphere.core.node

import com.psphere.core.ProtocolException
import com.psphere.core.copyExactly
import com.psphere.core.isValidHash
import com.psphere.core.model.Rec
import com.psphere.core.net.Msg
import com.psphere.core.net.OfferEntry
import com.psphere.core.net.Wire
import com.psphere.core.storage.Incoming
import com.psphere.core.storage.ReceiveResult

data class SyncProgress(val peerName: String, val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { CATALOG, PREVIEWS, SENDING, RECEIVING }
}

data class SyncResult(
    val peerId: String,
    val peerName: String,
    val sent: Int = 0,
    val received: Int = 0,
    val failed: Int = 0,
    /** Things the user should know that didn't stop the sync, e.g. "Laptop is full". */
    val notes: List<String> = emptyList(),
    /** Set when the sync didn't complete. */
    val error: String? = null,
)

/**
 * One full sync between this device and a linked [peer], run by both sides in lockstep.
 * Every step is "one side talks, the other listens", so neither can block the other:
 *
 *  1. catalog: exchange vectors, then each sends the records the other is missing
 *  2. previews: each asks for the previews it lacks
 *  3. files: the initiator offers what the peer should have, then the responder does
 *  4. catalog again, so both know who now holds what (Free up space relies on this)
 *  5. Bye
 */
internal class Session(
    private val node: Node,
    private val wire: Wire,
    private val peer: LinkedDevice,
    private val initiator: Boolean,
    private val onProgress: (SyncProgress) -> Unit,
    /** The responder has already read the initiator's first vector to know this is a sync. */
    private val firstPeerVector: Map<String, Long>? = null,
) {
    private var sent = 0
    private var received = 0
    private var failed = 0
    private val notes = LinkedHashSet<String>()

    fun run(): SyncResult {
        exchangeCatalog(firstPeerVector)
        if (initiator) { requestPreviews(); servePreviews() } else { servePreviews(); requestPreviews() }
        if (initiator) { push(); pull() } else { pull(); push() }
        exchangeCatalog(null)
        if (initiator) { wire.send(Msg.Bye); wire.expect<Msg.Bye>() } else { wire.expect<Msg.Bye>(); wire.send(Msg.Bye) }
        return result(null)
    }

    fun result(error: String?) = SyncResult(peer.id, peer.name, sent, received, failed, notes.toList(), error)

    private fun progress(stage: SyncProgress.Stage, done: Int, total: Int) =
        onProgress(SyncProgress(peer.name, stage, done, total))

    // ── 1. Catalog ───────────────────────────────────────────────────────────

    private fun exchangeCatalog(alreadyRead: Map<String, Long>?) {
        progress(SyncProgress.Stage.CATALOG, 0, 0)
        val cat = node.catalog
        val theirs: Map<String, Long>
        if (initiator) {
            wire.send(Msg.Vector(cat.vector()))
            theirs = wire.expect<Msg.Vector>().known
            sendRecords(cat.delta(theirs)); receiveRecords()
        } else {
            theirs = alreadyRead ?: wire.expect<Msg.Vector>().known
            wire.send(Msg.Vector(cat.vector()))
            receiveRecords(); sendRecords(cat.delta(theirs))
        }
    }

    private fun sendRecords(records: List<Rec>) {
        records.chunked(RECORDS_PER_FRAME).forEach { wire.send(Msg.Records(it)) }
        wire.send(Msg.End)
    }

    private fun receiveRecords() {
        var changed = false
        while (true) {
            when (val m = wire.receive()) {
                is Msg.Records -> m.records.forEach { if (node.catalog.apply(it)) changed = true }
                Msg.End -> break
                else -> throw ProtocolException("Unexpected ${m.typeName} while syncing the catalog")
            }
        }
        if (changed) node.onCatalogChanged()
    }

    // ── 2. Previews ──────────────────────────────────────────────────────────

    private fun requestPreviews() {
        val wanted = node.previewsWanted()
        val wantedSet = wanted.toHashSet()
        wire.send(Msg.PreviewWant(wanted))
        var n = 0
        while (true) {
            when (val m = wire.receive()) {
                is Msg.Preview -> {
                    if (m.hash in wantedSet) node.previews.put(m.hash, m.data)
                    progress(SyncProgress.Stage.PREVIEWS, ++n, wanted.size)
                }
                Msg.End -> return
                else -> throw ProtocolException("Unexpected ${m.typeName} while syncing previews")
            }
        }
    }

    private fun servePreviews() {
        val want = wire.expect<Msg.PreviewWant>()
        for (hash in want.hashes) {
            if (!isValidHash(hash)) continue
            node.previews.get(hash)?.let { wire.send(Msg.Preview(hash, it)) }
        }
        wire.send(Msg.End)
    }

    // ── 3. Files ─────────────────────────────────────────────────────────────

    private fun push() {
        val offers = node.offersFor(peer.id)
        wire.send(Msg.Offer(offers))
        val want = wire.expect<Msg.Want>()
        notes += want.notes
        val total = want.wanted.size
        want.wanted.forEachIndexed { done, (index, offset) ->
            progress(SyncProgress.Stage.SENDING, done, total)
            val entry = offers.getOrNull(index) ?: throw ProtocolException("${peer.name} asked for a file that wasn't offered")
            val source = node.openLocal(entry.hash)
            if (source == null || offset < 0 || offset > source.size) {
                source?.close()
                wire.send(Msg.FileData(index, offset, -1))
                failed++
                return@forEachIndexed
            }
            source.use { src ->
                com.psphere.core.skipExactly(src.stream, offset)
                wire.send(Msg.FileData(index, offset, src.size - offset))
                copyExactly(src.stream, wire.output, src.size - offset)
                wire.flush()
            }
            val ack = wire.expect<Msg.Ack>()
            if (ack.ok) sent++ else { failed++; if (ack.reason.isNotBlank()) notes += "${peer.name}: ${ack.reason}" }
        }
        progress(SyncProgress.Stage.SENDING, total, total)
        wire.send(Msg.End)
    }

    private fun pull() {
        val offer = wire.expect<Msg.Offer>().entries
        val plan = node.planIncoming(peer, offer)
        wire.send(Msg.Want(plan.wanted.map { it.index to it.offset }, plan.notes))
        notes += plan.notes
        val byIndex = plan.wanted.associateBy { it.index }
        var done = 0
        try {
            while (true) {
                val m = wire.receive()
                if (m == Msg.End) break
                val fd = m as? Msg.FileData ?: throw ProtocolException("Unexpected ${m.typeName} while receiving files")
                val w = byIndex[fd.index] ?: throw ProtocolException("${peer.name} sent a file that wasn't requested")
                progress(SyncProgress.Stage.RECEIVING, done++, plan.wanted.size)
                if (fd.length < 0) { failed++; continue }
                val result = node.receive(w.incoming, fd.offset, fd.length, wire.input)
                when (result) {
                    is ReceiveResult.Stored -> { received++; wire.send(Msg.Ack(fd.index, true, "")) }
                    is ReceiveResult.Rejected -> { failed++; wire.send(Msg.Ack(fd.index, false, result.reason)) }
                }
            }
            progress(SyncProgress.Stage.RECEIVING, plan.wanted.size, plan.wanted.size)
        } finally {
            plan.wanted.forEach { node.releaseClaim(it.incoming.hash) }
            node.flushKept()
        }
    }

    companion object {
        private const val RECORDS_PER_FRAME = 2_000
    }
}

/** A file this device asked the peer for. */
internal data class Wanted(val index: Int, val offset: Long, val incoming: Incoming)

internal data class IncomingPlan(val wanted: List<Wanted>, val notes: List<String>)
