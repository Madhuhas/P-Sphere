import Crypto
import Foundation
import SwiftASN1
import X509

/// SHA-256 of a public key (its SubjectPublicKeyInfo DER). Linked devices are pinned by this.
/// Matches `Fingerprint` in the Kotlin core byte for byte.
public struct Fingerprint: Equatable, Hashable, Sendable {
    public let bytes: [UInt8]

    public init?(bytes: [UInt8]) {
        guard bytes.count == 32 else { return nil }
        self.bytes = bytes
    }

    /// The fingerprint of a public key given as SubjectPublicKeyInfo DER.
    public init(spki: [UInt8]) {
        bytes = Array(SHA256.hash(data: spki))
    }

    public var hex: String { bytes.map { String(format: "%02x", $0) }.joined() }

    /// 16 lowercase base32 characters (80 bits), the same device ID the Kotlin core uses.
    public var deviceId: String { Fingerprint.base32(Array(bytes.prefix(10))) }

    static func base32(_ data: [UInt8]) -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567")
        var out = ""
        var buffer = 0
        var bits = 0
        for b in data {
            buffer = (buffer << 8) | Int(b)
            bits += 8
            while bits >= 5 {
                out.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return out
    }
}

/// This device's long-term P-256 key and self-signed certificate.
public struct DeviceIdentity: Sendable {
    public let privateKey: P256.Signing.PrivateKey
    public let certificateDER: [UInt8]

    public var fingerprint: Fingerprint { Fingerprint(spki: Array(privateKey.publicKey.derRepresentation)) }
    public var deviceId: String { fingerprint.deviceId }

    public static func generate() throws -> DeviceIdentity {
        let key = P256.Signing.PrivateKey()
        let id = Fingerprint(spki: Array(key.publicKey.derRepresentation)).deviceId
        let certKey = Certificate.PrivateKey(key)
        let name = try DistinguishedName { CommonName("PSphere \(id)") }
        let notAfter = DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(identifier: "UTC"),
                                      year: 2049, month: 12, day: 31, hour: 23, minute: 59, second: 59).date!
        let cert = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: certKey.publicKey,
            notValidBefore: Date().addingTimeInterval(-24 * 3600),
            notValidAfter: notAfter,
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: Certificate.Extensions {},
            issuerPrivateKey: certKey
        )
        var serializer = DER.Serializer()
        try serializer.serialize(cert)
        return DeviceIdentity(privateKey: key, certificateDER: serializer.serializedBytes)
    }

    /// Saved as: 4-byte format, then the raw private key and the certificate, each length-prefixed.
    public func save(to url: URL) throws {
        var w = ByteWriter()
        w.int32(1)
        w.bytes(Array(privateKey.rawRepresentation))
        w.bytes(certificateDER)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(w.data).write(to: url, options: .atomic)
    }

    public static func load(from url: URL) throws -> DeviceIdentity {
        var r = ByteReader(Array(try Data(contentsOf: url)))
        guard try r.int32() == 1 else { throw PSphereError.protocolError("Unknown identity file format") }
        let key = try P256.Signing.PrivateKey(rawRepresentation: try r.bytes(max: 1024))
        return DeviceIdentity(privateKey: key, certificateDER: try r.bytes(max: 65_536))
    }

    public static func loadOrCreate(at url: URL) throws -> DeviceIdentity {
        if FileManager.default.fileExists(atPath: url.path) { return try load(from: url) }
        let identity = try generate()
        try identity.save(to: url)
        return identity
    }
}

public enum Pairing {
    /// The 6-digit code both screens show when linking without a QR code. Derived from both keys,
    /// so a device in the middle would make the screens disagree. Same as `PairingOffer.shortCode`.
    public static func shortCode(_ a: Fingerprint, _ b: Fingerprint) -> String {
        let (x, y) = a.hex < b.hex ? (a, b) : (b, a)
        let h = Array(SHA256.hash(data: x.bytes + y.bytes))
        let n = (UInt64(h[0]) << 24) | (UInt64(h[1]) << 16) | (UInt64(h[2]) << 8) | UInt64(h[3])
        return String(format: "%06llu", n % 1_000_000)
    }
}

public enum PSphereError: Error, CustomStringConvertible {
    case protocolError(String)
    case peer(String)
    case closed

    public var description: String {
        switch self {
        case .protocolError(let m): return m
        case .peer(let m): return m
        case .closed: return "The connection was closed"
        }
    }
}

/// Writes a line to standard output straight away (unbuffered), for the command-line tool and tests.
public func log(_ line: String) {
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}
