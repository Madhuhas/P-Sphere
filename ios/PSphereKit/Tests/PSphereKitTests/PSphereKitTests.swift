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
}
