import Foundation

extension Rec {
    public var origin: String {
        switch self {
        case let .device(o, _, _), let .rule(o, _, _, _, _, _, _, _), let .item(o, _, _, _, _, _, _, _, _, _), let .hold(o, _, _, _): return o
        }
    }

    public var seq: Int64 {
        switch self {
        case let .device(_, s, _), let .rule(_, s, _, _, _, _, _, _), let .item(_, s, _, _, _, _, _, _, _, _), let .hold(_, s, _, _): return s
        }
    }

    /// The record's key within its origin, as `Rec.key` in the Kotlin core.
    public var key: String {
        switch self {
        case .device: return "d"
        case let .rule(_, _, ruleId, _, _, _, _, _): return "r:\(ruleId)"
        case let .item(_, _, ruleId, relPath, _, _, _, _, _, _): return "i:\(ruleId)/\(relPath)"
        case let .hold(_, _, hash, _): return "h:\(hash)"
        }
    }

    func withSeq(_ s: Int64) -> Rec {
        switch self {
        case let .device(o, _, n): return .device(origin: o, seq: s, name: n)
        case let .rule(o, _, id, l, d, i, sh, r): return .rule(origin: o, seq: s, ruleId: id, label: l, destinations: d, include: i, showInLibrary: sh, removed: r)
        case let .item(o, _, r, p, h, sz, m, t, me, st): return .item(origin: o, seq: s, ruleId: r, relPath: p, hash: h, size: sz, modified: m, takenAt: t, media: me, state: st)
        case let .hold(o, _, h, p): return .hold(origin: o, seq: s, hash: h, present: p)
        }
    }
}

/// The replicated catalog: every device's records, one writer per record, so replicas never
/// conflict. Same rules as `Catalog` in the Kotlin core.
public final class Catalog {
    public let selfId: String
    private var records: [String: Rec] = [:]
    private var known: [String: Int64] = [:]

    public init(selfId: String) { self.selfId = selfId }

    private func id(_ origin: String, _ key: String) -> String { origin + "\u{0}" + key }

    private func store(_ r: Rec) {
        records[id(r.origin, r.key)] = r
        if r.seq > (known[r.origin] ?? 0) { known[r.origin] = r.seq }
    }

    /// Records a change made by this device. Returns false if nothing actually changed.
    @discardableResult
    public func putOwn(_ r: Rec) -> Bool {
        precondition(r.origin == selfId, "Only this device's own records can be written")
        if let existing = records[id(r.origin, r.key)], existing.withSeq(0) == r.withSeq(0) { return false }
        store(r.withSeq((known[selfId] ?? 0) + 1))
        return true
    }

    /// Applies a record from another device. Returns true if it was new.
    @discardableResult
    public func apply(_ r: Rec) -> Bool {
        if let existing = records[id(r.origin, r.key)], existing.seq >= r.seq { return false }
        store(r)
        return true
    }

    public func vector() -> [String: Int64] { known }

    public func delta(_ peerKnown: [String: Int64]) -> [Rec] {
        records.values.filter { $0.seq > (peerKnown[$0.origin] ?? 0) }
            .sorted { ($0.origin, $0.seq) < ($1.origin, $1.seq) }
    }

    public func deviceName(_ origin: String) -> String? {
        if case let .device(_, _, name) = records[id(origin, "d")] { return name }
        return nil
    }

    public func rule(_ origin: String, _ ruleId: String) -> (label: String, destinations: [String], removed: Bool)? {
        if case let .rule(_, _, _, label, dest, _, _, removed) = records[id(origin, "r:\(ruleId)")] { return (label, dest, removed) }
        return nil
    }

    public func holders(_ hash: String) -> Set<String> {
        Set(records.values.compactMap { if case let .hold(o, _, h, true) = $0, h == hash { return o }; return nil })
    }

    // Saved as a count followed by the records, encoded as on the wire.
    public func save(to url: URL) throws {
        var w = ByteWriter()
        w.int32(Int32(records.count))
        records.values.sorted { ($0.origin, $0.seq) < ($1.origin, $1.seq) }.forEach { $0.write(to: &w) }
        try Data(w.data).write(to: url, options: .atomic)
    }

    public static func load(from url: URL, selfId: String) -> Catalog {
        let catalog = Catalog(selfId: selfId)
        guard let data = try? Data(contentsOf: url) else { return catalog }
        var r = ByteReader(Array(data))
        if let n = try? r.count() {
            for _ in 0..<n { if let rec = try? Rec.read(from: &r) { catalog.store(rec) } else { break } }
        }
        return catalog
    }
}

public func isValidHash(_ h: String) -> Bool {
    h.count == 64 && h.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
}
