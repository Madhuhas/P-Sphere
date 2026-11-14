import Foundation
import NIOCore
import NIOPosix
import NIOSSL

/// A device this one is linked with.
public struct LinkedDevice: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var fingerprintHex: String
}

/// What one sync achieved.
public struct SyncResult: Sendable {
    public var received = 0
    public var failed = 0
    public var notes: [String] = []
}

/// The Swift PSphere device: its identity, its links, its catalog, the backups it keeps for
/// other devices, and a server other devices connect to.
public actor Node {
    public let identity: DeviceIdentity
    public private(set) var name: String
    public private(set) var devices: [String: LinkedDevice]
    private let dataDir: URL
    private var pairingOpenUntil: Date?
    /// Shows the 6-digit code to the user and returns whether they confirmed it matches.
    private let confirmCode: @Sendable (String, String) async -> Bool
    let catalog: Catalog
    /// Where backups from other devices go. Nil until a backup location is chosen.
    let kept: KeptStore?
    /// Called after each sync another device ran with this one.
    private let onSync: @Sendable (String, SyncResult) -> Void

    public init(dataDir: URL, name: String, backupDir: URL? = nil,
                confirmCode: @escaping @Sendable (String, String) async -> Bool,
                onSync: @escaping @Sendable (String, SyncResult) -> Void = { _, _ in }) throws {
        self.dataDir = dataDir
        self.name = name
        self.confirmCode = confirmCode
        self.onSync = onSync
        identity = try DeviceIdentity.loadOrCreate(at: dataDir.appendingPathComponent("identity.bin"))
        let linksFile = dataDir.appendingPathComponent("links.json")
        if let data = try? Data(contentsOf: linksFile),
           let list = try? JSONDecoder().decode([LinkedDevice].self, from: data) {
            devices = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
        } else {
            devices = [:]
        }
        let id = identity.deviceId
        catalog = Catalog.load(from: dataDir.appendingPathComponent("catalog.bin"), selfId: id)
        kept = try backupDir.map { try KeptStore(root: $0) }
        // Other devices learn this device's name from its catalog.
        if !name.isEmpty { catalog.putOwn(.device(origin: id, seq: 0, name: name)) }
    }

    private func saveCatalog() { try? catalog.save(to: dataDir.appendingPathComponent("catalog.bin")) }

    public var id: String { identity.deviceId }

    /// Like "Link a device": accepts linking requests for 10 minutes.
    public func openPairing(for seconds: TimeInterval = 600) { pairingOpenUntil = Date().addingTimeInterval(seconds) }

    private func saveLinks() throws {
        let data = try JSONEncoder().encode(Array(devices.values).sorted { $0.id < $1.id })
        try data.write(to: dataDir.appendingPathComponent("links.json"), options: .atomic)
    }

    private func link(_ fp: Fingerprint, name: String) throws {
        devices[fp.deviceId] = LinkedDevice(id: fp.deviceId, name: name, fingerprintHex: fp.hex)
        try saveLinks()
    }

    // MARK: Serving

    /// Listens for other devices on `port` until the task is cancelled.
    public nonisolated func serve(host: String = "0.0.0.0", port: Int) async throws {
        let config = try Tls.configuration(identity: identity, server: true)
        let context = try NIOSSLContext(configuration: config)
        let server = try await ServerBootstrap(group: MultiThreadedEventLoopGroup.singleton)
            .serverChannelOption(.socketOption(.so_reuseaddr), value: 1)
            .bind(host: host, port: port) { channel in
                channel.eventLoop.makeCompletedFuture {
                    let box = PeerCertificateBox()
                    let tls = NIOSSLServerHandler(context: context) { certs, promise in
                        box.certificate = certs.first
                        promise.succeed(.certificateVerified)
                    }
                    try channel.pipeline.syncOperations.addHandler(tls)
                    let async = try NIOAsyncChannel<ByteBuffer, ByteBuffer>(wrappingChannelSynchronously: channel)
                    return (async, box)
                }
            }
        try await withThrowingDiscardingTaskGroup { group in
            try await server.executeThenClose { inbound in
                for try await (child, box) in inbound {
                    group.addTask { await self.handle(child, box) }
                }
            }
        }
    }

    private nonisolated func handle(_ channel: NIOAsyncChannel<ByteBuffer, ByteBuffer>, _ box: PeerCertificateBox) async {
        do {
            try await channel.executeThenClose { inbound, outbound in
                let conn = try await Connection.accepted(inbound: inbound, outbound: outbound, box: box)
                try await self.serveConnection(conn)
            }
        } catch {
            log("Connection ended: \(error)")
        }
    }

    private func serveConnection(_ conn: Connection) async throws {
        let fp = conn.peerFingerprint
        guard case let .hello(version, peerId, peerName) = try await conn.receive() else {
            throw PSphereError.protocolError("Expected Hello")
        }
        if version != Msg.version {
            try await conn.send(.error("\(name) has a different version of PSphere. Update PSphere on both devices."))
            return
        }
        if peerId != fp.deviceId { try await conn.send(.error("Identity mismatch")); return }
        let linked = devices[fp.deviceId].map { $0.fingerprintHex == fp.hex } ?? false
        try await conn.send(.hello(version: Msg.version, deviceId: id, name: name))
        if !linked { try await handlePairing(conn, fp, peerName); return }
        guard let peer = devices[fp.deviceId] else { return }
        switch try await conn.receive() {
        case let .vector(known):
            let result = try await serveSync(conn, peer: peer, firstVector: known)
            onSync(peer.name, result)
        default:
            try await conn.send(.error("Unexpected request"))
        }
    }

    // MARK: Sync (this device answering)

    /// The responder side of one sync, step for step as `Session` in the Kotlin core:
    /// catalog, previews, files, catalog again, Bye.
    private func serveSync(_ conn: Connection, peer: LinkedDevice, firstVector: [String: Int64]) async throws -> SyncResult {
        var result = SyncResult()
        try await exchangeCatalog(conn, alreadyRead: firstVector)
        // Previews: answer first (this device has none to give yet), then ask (none wanted yet).
        guard case .previewWant = try await conn.receive() else { throw PSphereError.protocolError("Expected PreviewWant") }
        try await conn.send(.end)
        try await conn.send(.previewWant([]))
        previews: while true {
            switch try await conn.receive() {
            case .end: break previews
            case .preview: continue
            case let m: throw PSphereError.protocolError("Unexpected \(m) while syncing previews")
            }
        }
        // Files: receive what the other device offers, then offer this device's (none yet).
        try await pull(conn, peer: peer, into: &result)
        try await conn.send(.offer([]))
        guard case .want = try await conn.receive() else { throw PSphereError.protocolError("Expected Want") }
        try await conn.send(.end)
        try await exchangeCatalog(conn, alreadyRead: nil)
        guard case .bye = try await conn.receive() else { throw PSphereError.protocolError("Expected Bye") }
        try await conn.send(.bye)
        saveCatalog()
        return result
    }

    private func exchangeCatalog(_ conn: Connection, alreadyRead: [String: Int64]?) async throws {
        let theirs: [String: Int64]
        if let alreadyRead { theirs = alreadyRead } else {
            guard case let .vector(v) = try await conn.receive() else { throw PSphereError.protocolError("Expected Vector") }
            theirs = v
        }
        try await conn.send(.vector(catalog.vector()))
        records: while true {
            switch try await conn.receive() {
            case let .records(recs): recs.forEach { catalog.apply($0) }
            case .end: break records
            case let m: throw PSphereError.protocolError("Unexpected \(m) while syncing the catalog")
            }
        }
        let delta = catalog.delta(theirs)
        for start in stride(from: 0, to: delta.count, by: 2_000) {
            try await conn.send(.records(Array(delta[start..<min(start + 2_000, delta.count)])))
        }
        try await conn.send(.end)
    }

    /// Receives the files the other device offers, checking each one's SHA-256.
    private func pull(_ conn: Connection, peer: LinkedDevice, into result: inout SyncResult) async throws {
        guard case let .offer(entries) = try await conn.receive() else { throw PSphereError.protocolError("Expected Offer") }
        var wanted: [(Int32, Int64)] = []
        var targets: [Int32: (URL, String)] = [:]
        var notes: [String] = []
        if let kept {
            for (i, e) in entries.enumerated() {
                guard isValidHash(e.hash), e.size >= 0, e.origin != id,
                      let rule = catalog.rule(e.origin, e.ruleId), !rule.removed, rule.destinations.contains(id),
                      let target = kept.location(originName: catalog.deviceName(e.origin) ?? e.origin, label: rule.label, relPath: e.relPath)
                else { continue }
                if kept.has(target, hash: e.hash) { holds(e.hash); continue }
                wanted.append((Int32(i), 0))
                targets[Int32(i)] = (target, e.hash)
            }
        } else if !entries.isEmpty {
            notes.append("\(name) has no backup location set")
        }
        try await conn.send(.want(wanted: wanted, notes: notes))
        result.notes += notes
        while true {
            let m = try await conn.receive()
            if case .end = m { break }
            guard case let .fileData(index, _, length) = m, let wantedFile = targets[index], let kept else {
                throw PSphereError.protocolError("\(peer.name) sent a file that wasn't requested")
            }
            let (target, hash) = wantedFile
            if length < 0 { result.failed += 1; continue }
            let incoming = try kept.begin(target: target, hash: hash)
            do {
                var left = length
                while left > 0 {
                    let chunk = try await conn.receiveRawChunk(max: left)
                    try incoming.write(chunk)
                    left -= Int64(chunk.count)
                }
            } catch {
                kept.cancel(incoming)
                throw error
            }
            if let reason = kept.finish(incoming) {
                result.failed += 1
                try await conn.send(.ack(index: index, ok: false, reason: reason))
            } else {
                result.received += 1
                holds(hash)
                try await conn.send(.ack(index: index, ok: true, reason: ""))
            }
        }
        saveCatalog()
    }

    /// Tells other devices (through the catalog) that this device now holds a copy of `hash`.
    private func holds(_ hash: String) { catalog.putOwn(.hold(origin: id, seq: 0, hash: hash, present: true)) }

    /// The responder side of linking, as `Node.handlePairing` in the Kotlin core.
    private func handlePairing(_ conn: Connection, _ fp: Fingerprint, _ peerName: String) async throws {
        guard case let .pairRequest(_, codeMode) = try await conn.receive() else {
            throw PSphereError.protocolError("Expected a link request")
        }
        guard let until = pairingOpenUntil, Date() < until else {
            try await conn.send(.error("\(name) isn't ready to link. Open “Link a device” on it first."))
            return
        }
        guard codeMode else {
            try await conn.send(.error("Scan the QR code on \(name) instead."))
            return
        }
        let code = Pairing.shortCode(identity.fingerprint, fp)
        let mine = await confirmCode(peerName, code)
        guard case let .pairConfirm(theirs) = try await conn.receive() else {
            throw PSphereError.protocolError("Expected a confirmation")
        }
        if !mine || !theirs { try await conn.send(.error("Linking was cancelled")); return }
        pairingOpenUntil = nil
        // Save the link only after the other device says it saved its side.
        try await conn.send(.pairOk(name: name))
        guard case .pairDone = try await conn.receive() else { throw PSphereError.protocolError("Expected PairDone") }
        try link(fp, name: peerName)
    }
}
