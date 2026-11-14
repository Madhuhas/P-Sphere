import XCTest
@testable import PSphereKit

final class PSphereKitTests: XCTestCase {
    func testDeviceIdIsBase32OfTheFirstTenFingerprintBytes() {
        XCTAssertEqual(Fingerprint.base32(Array(0..<10)), "aaaqeayeaudaocaj")
    }

    func testShortCodeMatchesTheKotlinRuleInEitherOrder() {
        let a = Fingerprint(bytes: [UInt8](repeating: 1, count: 32))!
        let b = Fingerprint(bytes: [UInt8](repeating: 2, count: 32))!
        XCTAssertEqual(Pairing.shortCode(a, b), "367443")
        XCTAssertEqual(Pairing.shortCode(b, a), "367443")
    }

    func testMessagesSurviveEncodingAndDecoding() throws {
        let messages: [Msg] = [
            .hello(version: 4, deviceId: "abc", name: "iPad · Ünïcode"),
            .pairRequest(token: "", codeMode: true),
            .pairConfirm(accepted: true),
            .pairOk(name: "Laptop"),
            .pairDone,
            .want(wanted: [(0, 0), (2, 1234)], notes: ["Laptop is full"]),
            .offer([OfferEntry(hash: "h", size: 5, origin: "o", ruleId: "r", relPath: "Camera/a.jpg")]),
            .records([.item(origin: "o", seq: 3, ruleId: "r", relPath: "a.jpg", hash: "h", size: 1, modified: 2, takenAt: 3, media: 0, state: 0)]),
        ]
        for m in messages {
            let (type, payload) = m.encoded()
            XCTAssertEqual(try Msg.decode(type: type, payload: payload), m)
        }
    }

    func testHelloIsLaidOutLikeJavasDataOutputStream() {
        let (type, payload) = Msg.hello(version: 4, deviceId: "ab", name: "c").encoded()
        XCTAssertEqual(type, 1)
        XCTAssertEqual(payload, [0, 0, 0, 4, 0, 0, 0, 2, 0x61, 0x62, 0, 0, 0, 1, 0x63])
    }

    func testIdentitySurvivesSavingAndLoading() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let file = dir.appendingPathComponent("identity.bin")
        let created = try DeviceIdentity.loadOrCreate(at: file)
        let loaded = try DeviceIdentity.loadOrCreate(at: file)
        XCTAssertEqual(created.deviceId, loaded.deviceId)
        XCTAssertEqual(created.certificateDER, loaded.certificateDER)
        XCTAssertEqual(created.deviceId.count, 16)
    }

    func testBackupsCanNotEscapeTheBackupFolder() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try KeptStore(root: root)
        let ok = store.location(originName: "Laptop", label: "Camera", relPath: "Trip/Day 2/a (1).jpg")
        XCTAssertEqual(ok?.path, root.appendingPathComponent("Laptop/Camera/Trip/Day 2/a (1).jpg").path)
        XCTAssertNil(store.location(originName: "Laptop", label: "Camera", relPath: "../../etc/passwd"))
        XCTAssertNil(store.location(originName: "..", label: "Camera", relPath: "a.jpg"))
        XCTAssertNil(store.location(originName: "Laptop", label: "Camera", relPath: #"a\..\b.jpg"#))
        // A device name with a slash can't add folders either.
        XCTAssertEqual(store.location(originName: "My/Phone", label: "Camera", relPath: "a.jpg")?.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "My_Phone")
    }

    func testCatalogKeepsOneWriterPerRecordAndSendsOnlyWhatIsMissing() {
        let mine = Catalog(selfId: "me")
        mine.putOwn(.device(origin: "me", seq: 0, name: "iPad"))
        XCTAssertFalse(mine.putOwn(.device(origin: "me", seq: 0, name: "iPad")), "no change, no new record")
        mine.putOwn(.device(origin: "me", seq: 0, name: "Maya's iPad"))
        XCTAssertEqual(mine.vector()["me"], 2)

        XCTAssertTrue(mine.apply(.device(origin: "lap", seq: 5, name: "Laptop")))
        XCTAssertFalse(mine.apply(.device(origin: "lap", seq: 4, name: "Old name")), "older loses")
        XCTAssertEqual(mine.deviceName("lap"), "Laptop")

        let delta = mine.delta(["me": 2, "lap": 0])
        XCTAssertEqual(delta, [.device(origin: "lap", seq: 5, name: "Laptop")])
    }

    func testAFileOnlyCountsWhenItsHashMatches() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try KeptStore(root: root)
        let target = store.location(originName: "Laptop", label: "Docs", relPath: "n.txt")!
        let good = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" // sha256("hello")
        let a = try store.begin(target: target, hash: good)
        try a.write(Array("hello".utf8))
        XCTAssertNil(store.finish(a))
        XCTAssertTrue(store.has(target, hash: good))
        let b = try store.begin(target: target, hash: good)
        try b.write(Array("tampered".utf8))
        XCTAssertNotNil(store.finish(b), "a damaged file is rejected")
        XCTAssertTrue(store.has(target, hash: good), "and the good copy stays")
    }
}
