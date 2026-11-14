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

/// The Swift PSphere device: its identity, its links, and a server other devices connect to.
/// This first version answers linking requests; backing up comes next.
public actor Node {
    public let identity: DeviceIdentity
    public private(set) var name: String
    public private(set) var devices: [String: LinkedDevice]
    private let dataDir: URL
    private var pairingOpenUntil: Date?
    /// Shows the 6-digit code to the user and returns whether they confirmed it matches.
    private let confirmCode: @Sendable (String, String) async -> Bool

    public init(dataDir: URL, name: String, confirmCode: @escaping @Sendable (String, String) async -> Bool) throws {
        self.dataDir = dataDir
        self.name = name
        self.confirmCode = confirmCode
        identity = try DeviceIdentity.loadOrCreate(at: dataDir.appendingPathComponent("identity.bin"))
        let linksFile = dataDir.appendingPathComponent("links.json")
        if let data = try? Data(contentsOf: linksFile),
           let list = try? JSONDecoder().decode([LinkedDevice].self, from: data) {
            devices = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
        } else {
            devices = [:]
        }
    }

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
        try await conn.send(.error("\(name) can't back up with other devices yet"))
    }

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
