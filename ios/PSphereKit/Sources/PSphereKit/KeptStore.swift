import Crypto
import Foundation

/// Backups this device keeps for other devices, laid out as
/// `<backup location>/<sending device>/<folder name>/<path>`, like the other PSphere apps.
/// A file only counts as stored once its SHA-256 matches what the sender announced.
public final class KeptStore {
    public let root: URL
    private let fm = FileManager.default

    public init(root: URL) throws {
        self.root = root
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
    }

    /// A safe file name for one path part: no separators, no "." or "..".
    static func safePart(_ s: String) -> String? {
        let cleaned = s.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
            .trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty, cleaned != ".", cleaned != ".." else { return nil }
        return cleaned
    }

    /// Where a file from `originName`'s folder `label` with relative path `relPath` is kept.
    /// Nil if the path tries to leave the backup location.
    public func location(originName: String, label: String, relPath: String) -> URL? {
        guard let device = KeptStore.safePart(originName), let folder = KeptStore.safePart(label) else { return nil }
        var url = root.appendingPathComponent(device).appendingPathComponent(folder)
        for part in relPath.split(separator: "/", omittingEmptySubsequences: true) {
            guard part != ".", part != "..", !part.contains("\\") else { return nil }
            url.appendPathComponent(String(part))
        }
        return url
    }

    /// True if the file at `url` already has this SHA-256.
    public func has(_ url: URL, hash: String) -> Bool {
        guard fm.fileExists(atPath: url.path), let digest = try? KeptStore.sha256(of: url) else { return false }
        return digest == hash
    }

    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Receives a file through `write`-style chunks into a temporary file, checks it, then moves
    /// it into place. A changed file keeps the previous version in `.psphere/old/<date>/`.
    public final class Incoming {
        let target: URL
        let temp: URL
        let expected: String
        let handle: FileHandle
        var hasher = SHA256()

        init(target: URL, temp: URL, expected: String) throws {
            self.target = target
            self.temp = temp
            self.expected = expected
            _ = FileManager.default.createFile(atPath: temp.path, contents: nil)
            handle = try FileHandle(forWritingTo: temp)
        }

        public func write(_ bytes: [UInt8]) throws {
            hasher.update(data: bytes)
            try handle.write(contentsOf: bytes)
        }
    }

    public func begin(target: URL, hash: String) throws -> Incoming {
        let partDir = root.appendingPathComponent(".psphere/partial")
        try fm.createDirectory(at: partDir, withIntermediateDirectories: true)
        return try Incoming(target: target, temp: partDir.appendingPathComponent(hash), expected: hash)
    }

    /// Finishes a file. Returns nil when stored, or the reason it was rejected.
    public func finish(_ inc: Incoming) -> String? {
        try? inc.handle.close()
        let got = inc.hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard got == inc.expected else {
            try? fm.removeItem(at: inc.temp)
            return "the file arrived damaged"
        }
        do {
            try fm.createDirectory(at: inc.target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: inc.target.path) {
                let day = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withFullDate])
                let rel = inc.target.path.dropFirst(root.path.count + 1)
                let old = root.appendingPathComponent(".psphere/old/\(day)").appendingPathComponent(String(rel))
                try fm.createDirectory(at: old.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fm.fileExists(atPath: old.path) { try fm.removeItem(at: old) }
                try fm.moveItem(at: inc.target, to: old)
            }
            try fm.moveItem(at: inc.temp, to: inc.target)
            return nil
        } catch {
            try? fm.removeItem(at: inc.temp)
            return "couldn't save the file"
        }
    }

    public func cancel(_ inc: Incoming) {
        try? inc.handle.close()
        try? fm.removeItem(at: inc.temp)
    }
}
