import Foundation

/// Big-endian writer matching Java's DataOutputStream, as the Kotlin core's `Codec` uses it.
public struct ByteWriter {
    public private(set) var data: [UInt8] = []
    public init() {}

    public mutating func byte(_ v: UInt8) { data.append(v) }
    public mutating func bool(_ v: Bool) { data.append(v ? 1 : 0) }
    public mutating func int32(_ v: Int32) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
    public mutating func int64(_ v: Int64) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
    public mutating func str(_ s: String) { bytes(Array(s.utf8)) }
    public mutating func bytes(_ b: [UInt8]) { int32(Int32(b.count)); data.append(contentsOf: b) }
    public mutating func strList(_ list: [String]) { int32(Int32(list.count)); list.forEach { str($0) } }
}

/// Big-endian reader matching Java's DataInputStream. Throws on truncated or oversized input.
public struct ByteReader {
    private let data: [UInt8]
    private var pos = 0
    public init(_ data: [UInt8]) { self.data = data }

    public var remaining: Int { data.count - pos }

    private mutating func take(_ n: Int) throws -> ArraySlice<UInt8> {
        guard n >= 0, pos + n <= data.count else { throw PSphereError.protocolError("Message ended early") }
        defer { pos += n }
        return data[pos..<pos + n]
    }

    public mutating func byte() throws -> UInt8 { try take(1).first! }
    public mutating func bool() throws -> Bool { try byte() != 0 }
    public mutating func int32() throws -> Int32 { try take(4).reduce(0) { ($0 << 8) | Int32($1) } }
    public mutating func int64() throws -> Int64 { try take(8).reduce(0) { ($0 << 8) | Int64($1) } }

    public mutating func bytes(max: Int) throws -> [UInt8] {
        let n = Int(try int32())
        guard n >= 0, n <= max else { throw PSphereError.protocolError("Invalid data length (\(n))") }
        return Array(try take(n))
    }

    public mutating func str() throws -> String {
        guard let s = String(bytes: try bytes(max: 64 * 1024), encoding: .utf8) else {
            throw PSphereError.protocolError("Invalid text")
        }
        return s
    }

    public mutating func count(max: Int = 10_000_000) throws -> Int {
        let n = Int(try int32())
        guard n >= 0, n <= max else { throw PSphereError.protocolError("Invalid list length (\(n))") }
        return n
    }

    public mutating func strList(max: Int = 1_000_000) throws -> [String] {
        try (0..<count(max: max)).map { _ in try str() }
    }
}

public struct OfferEntry: Equatable, Sendable {
    public var hash: String, size: Int64, origin: String, ruleId: String, relPath: String
    public init(hash: String, size: Int64, origin: String, ruleId: String, relPath: String) {
        self.hash = hash; self.size = size; self.origin = origin; self.ruleId = ruleId; self.relPath = relPath
    }
}

/// The protocol messages, numbered exactly as `Wire.encode` in the Kotlin core numbers them.
/// Each travels as a frame: type (1 byte), payload length (4 bytes), payload.
public enum Msg: Equatable, Sendable {
    case hello(version: Int32, deviceId: String, name: String)            // 1
    case vector([String: Int64])                                           // 2
    case records([Rec])                                                    // 3
    case previewWant([String])                                             // 4
    case preview(hash: String, data: [UInt8])                              // 5
    case offer([OfferEntry])                                               // 6
    case want(wanted: [(Int32, Int64)], notes: [String])                   // 7
    case fileData(index: Int32, offset: Int64, length: Int64)              // 8
    case ack(index: Int32, ok: Bool, reason: String)                       // 9
    case get(hash: String, offset: Int64)                                  // 10
    case has([String])                                                     // 11
    case hasReply([String])                                                // 12
    case pairRequest(token: String, codeMode: Bool)                        // 13
    case pairConfirm(accepted: Bool)                                       // 14
    case pairOk(name: String)                                              // 15
    case error(String)                                                     // 16
    case end                                                               // 17
    case bye                                                               // 18
    case pairDone                                                          // 19

    public static let version: Int32 = 4
    public static let maxFrame = 64 * 1024 * 1024
    public static let maxPreview = 2 * 1024 * 1024

    public static func == (a: Msg, b: Msg) -> Bool { a.encoded().type == b.encoded().type && a.encoded().payload == b.encoded().payload }

    public func encoded() -> (type: UInt8, payload: [UInt8]) {
        var w = ByteWriter()
        let type: UInt8
        switch self {
        case let .hello(v, id, name): w.int32(v); w.str(id); w.str(name); type = 1
        case let .vector(known):
            w.int32(Int32(known.count)); known.forEach { w.str($0.key); w.int64($0.value) }; type = 2
        case let .records(recs): w.int32(Int32(recs.count)); recs.forEach { $0.write(to: &w) }; type = 3
        case let .previewWant(h): w.strList(h); type = 4
        case let .preview(h, d): w.str(h); w.bytes(d); type = 5
        case let .offer(entries):
            w.int32(Int32(entries.count))
            entries.forEach { w.str($0.hash); w.int64($0.size); w.str($0.origin); w.str($0.ruleId); w.str($0.relPath) }
            type = 6
        case let .want(wanted, notes):
            w.int32(Int32(wanted.count)); wanted.forEach { w.int32($0.0); w.int64($0.1) }; w.strList(notes); type = 7
        case let .fileData(i, off, len): w.int32(i); w.int64(off); w.int64(len); type = 8
        case let .ack(i, ok, reason): w.int32(i); w.bool(ok); w.str(reason); type = 9
        case let .get(h, off): w.str(h); w.int64(off); type = 10
        case let .has(h): w.strList(h); type = 11
        case let .hasReply(h): w.strList(h); type = 12
        case let .pairRequest(t, code): w.str(t); w.bool(code); type = 13
        case let .pairConfirm(a): w.bool(a); type = 14
        case let .pairOk(n): w.str(n); type = 15
        case let .error(m): w.str(String(m.prefix(500))); type = 16
        case .end: type = 17
        case .bye: type = 18
        case .pairDone: type = 19
        }
        return (type, w.data)
    }

    public static func decode(type: UInt8, payload: [UInt8]) throws -> Msg {
        var r = ByteReader(payload)
        switch type {
        case 1: return .hello(version: try r.int32(), deviceId: try r.str(), name: try r.str())
        case 2:
            var m: [String: Int64] = [:]
            for _ in 0..<(try r.count()) { m[try r.str()] = try r.int64() }
            return .vector(m)
        case 3: return .records(try (0..<r.count()).map { _ in try Rec.read(from: &r) })
        case 4: return .previewWant(try r.strList())
        case 5: return .preview(hash: try r.str(), data: try r.bytes(max: maxPreview))
        case 6:
            return .offer(try (0..<r.count()).map { _ in
                OfferEntry(hash: try r.str(), size: try r.int64(), origin: try r.str(), ruleId: try r.str(), relPath: try r.str())
            })
        case 7:
            let wanted = try (0..<r.count()).map { _ in (try r.int32(), try r.int64()) }
            return .want(wanted: wanted, notes: try r.strList(max: 100))
        case 8: return .fileData(index: try r.int32(), offset: try r.int64(), length: try r.int64())
        case 9: return .ack(index: try r.int32(), ok: try r.bool(), reason: try r.str())
        case 10: return .get(hash: try r.str(), offset: try r.int64())
        case 11: return .has(try r.strList())
        case 12: return .hasReply(try r.strList())
        case 13: return .pairRequest(token: try r.str(), codeMode: try r.bool())
        case 14: return .pairConfirm(accepted: try r.bool())
        case 15: return .pairOk(name: try r.str())
        case 16: return .error(try r.str())
        case 17: return .end
        case 18: return .bye
        case 19: return .pairDone
        default: throw PSphereError.protocolError("Unknown message type \(type)")
        }
    }
}

public enum Include: UInt8, Sendable { case media = 0, all = 1 }
public enum Media: UInt8, Sendable { case photo = 0, video = 1, other = 2 }
public enum ItemState: UInt8, Sendable { case present = 0, freed = 1, deleted = 2 }

/// Catalog records, encoded as `Codec.rec` in the Kotlin core.
public enum Rec: Equatable, Sendable {
    case device(origin: String, seq: Int64, name: String)
    case rule(origin: String, seq: Int64, ruleId: String, label: String, destinations: [String], include: UInt8, showInLibrary: Bool, removed: Bool)
    case item(origin: String, seq: Int64, ruleId: String, relPath: String, hash: String, size: Int64, modified: Int64, takenAt: Int64, media: UInt8, state: UInt8)
    case hold(origin: String, seq: Int64, hash: String, present: Bool)

    func write(to w: inout ByteWriter) {
        switch self {
        case let .device(o, s, n): w.byte(1); w.str(o); w.int64(s); w.str(n)
        case let .rule(o, s, id, label, dest, inc, show, removed):
            w.byte(2); w.str(o); w.int64(s); w.str(id); w.str(label); w.strList(dest); w.byte(inc); w.bool(show); w.bool(removed)
        case let .item(o, s, rule, path, hash, size, mod, taken, media, state):
            w.byte(3); w.str(o); w.int64(s); w.str(rule); w.str(path); w.str(hash); w.int64(size); w.int64(mod)
            w.int64(taken); w.byte(media); w.byte(state)
        case let .hold(o, s, h, p): w.byte(4); w.str(o); w.int64(s); w.str(h); w.bool(p)
        }
    }

    static func read(from r: inout ByteReader) throws -> Rec {
        switch try r.byte() {
        case 1: return .device(origin: try r.str(), seq: try r.int64(), name: try r.str())
        case 2:
            return .rule(origin: try r.str(), seq: try r.int64(), ruleId: try r.str(), label: try r.str(),
                         destinations: try r.strList(max: 64), include: try r.byte(), showInLibrary: try r.bool(), removed: try r.bool())
        case 3:
            return .item(origin: try r.str(), seq: try r.int64(), ruleId: try r.str(), relPath: try r.str(), hash: try r.str(),
                         size: try r.int64(), modified: try r.int64(), takenAt: try r.int64(), media: try r.byte(), state: try r.byte())
        case 4: return .hold(origin: try r.str(), seq: try r.int64(), hash: try r.str(), present: try r.bool())
        case let k: throw PSphereError.protocolError("Unknown record type (\(k))")
        }
    }
}
