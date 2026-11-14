import NIOCore
import NIOPosix
import NIOSSL

/// Holds the certificate the other device presented during the TLS handshake.
final class PeerCertificateBox: @unchecked Sendable {
    var certificate: NIOSSLCertificate?
}

/// One encrypted connection to another device, read and written as PSphere frames.
public final class Connection: @unchecked Sendable {
    private let outbound: NIOAsyncChannelOutboundWriter<ByteBuffer>
    private var inbound: NIOAsyncChannelInboundStream<ByteBuffer>.AsyncIterator
    private var buffer = ByteBuffer()
    /// The other device's key fingerprint, taken from its certificate once the handshake is done.
    public private(set) var peerFingerprint: Fingerprint

    private init(inbound: NIOAsyncChannelInboundStream<ByteBuffer>, outbound: NIOAsyncChannelOutboundWriter<ByteBuffer>) {
        self.outbound = outbound
        self.inbound = inbound.makeAsyncIterator()
        self.peerFingerprint = Fingerprint(spki: [])
    }

    /// Wraps an accepted connection. Data only flows after the TLS handshake, and the other device
    /// speaks first, so once its first bytes arrive its certificate is known.
    static func accepted(inbound: NIOAsyncChannelInboundStream<ByteBuffer>,
                         outbound: NIOAsyncChannelOutboundWriter<ByteBuffer>,
                         box: PeerCertificateBox) async throws -> Connection {
        let conn = Connection(inbound: inbound, outbound: outbound)
        try await conn.fill(1)
        conn.peerFingerprint = try Tls.fingerprint(of: box.certificate)
        return conn
    }

    public func send(_ msg: Msg) async throws {
        let (type, payload) = msg.encoded()
        var out = ByteBuffer()
        out.writeInteger(type)
        out.writeInteger(Int32(payload.count))
        out.writeBytes(payload)
        try await outbound.write(out)
    }

    /// Writes raw bytes, e.g. file contents after a `fileData` frame.
    public func sendRaw(_ bytes: [UInt8]) async throws {
        try await outbound.write(ByteBuffer(bytes: bytes))
    }

    private func fill(_ n: Int) async throws {
        while buffer.readableBytes < n {
            guard var more = try await inbound.next() else { throw PSphereError.closed }
            buffer.writeBuffer(&more)
        }
    }

    /// The next message. An `error` from the other device is thrown as `PSphereError.peer`.
    public func receive() async throws -> Msg {
        try await fill(5)
        let type: UInt8 = buffer.readInteger()!
        let len = Int(buffer.readInteger(as: Int32.self)!)
        guard len >= 0, len <= Msg.maxFrame else { throw PSphereError.protocolError("Message is too large") }
        try await fill(len)
        let payload = buffer.readBytes(length: len)!
        let msg = try Msg.decode(type: type, payload: payload)
        if case .error(let m) = msg { throw PSphereError.peer(m) }
        return msg
    }

    /// Reads the next chunk of raw bytes (at most `max`), e.g. file contents after a `fileData`
    /// frame. Callers loop until they have read the whole file.
    public func receiveRawChunk(max: Int64) async throws -> [UInt8] {
        if buffer.readableBytes == 0 {
            guard var more = try await inbound.next() else { throw PSphereError.closed }
            buffer.writeBuffer(&more)
        }
        return buffer.readBytes(length: Int(min(Int64(buffer.readableBytes), max)))!
    }
}

enum Tls {
    /// Mutual TLS: both sides present their self-signed certificate, the TLS layer accepts any
    /// certificate, and the caller decides who the peer is from its key fingerprint, before
    /// reading anything else. Same rules as the Kotlin core's `Tls`.
    static func configuration(identity: DeviceIdentity, server: Bool) throws -> TLSConfiguration {
        let cert = try NIOSSLCertificate(bytes: identity.certificateDER, format: .der)
        let key = try NIOSSLPrivateKey(bytes: Array(identity.privateKey.derRepresentation), format: .der)
        var config = server
            ? TLSConfiguration.makeServerConfiguration(certificateChain: [.certificate(cert)], privateKey: .privateKey(key))
            : TLSConfiguration.makeClientConfiguration()
        if !server {
            config.certificateChain = [.certificate(cert)]
            config.privateKey = .privateKey(key)
        }
        config.minimumTLSVersion = .tlsv12
        config.maximumTLSVersion = .tlsv13
        // Ask for (and require) the peer's certificate; the custom callback below accepts it.
        config.certificateVerification = .noHostnameVerification
        return config
    }

    static func fingerprint(of cert: NIOSSLCertificate?) throws -> Fingerprint {
        guard let cert else { throw PSphereError.protocolError("The other device didn't identify itself") }
        return Fingerprint(spki: try cert.extractPublicKey().toSPKIBytes())
    }
}
