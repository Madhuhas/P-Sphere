// A command-line Swift PSphere device, for testing the Swift engine against the Kotlin one.
//
//   psphere-swift serve <dir> <port> <name>   open "Link a device" and listen; codes are confirmed automatically
//   psphere-swift status <dir>                this device's ID and its linked devices
import Foundation
import PSphereKit

let args = CommandLine.arguments.dropFirst()
guard let command = args.first, let dir = args.dropFirst().first else {
    log("usage: psphere-swift serve <dir> <port> <name> | status <dir>")
    exit(2)
}
let dataDir = URL(fileURLWithPath: dir)

switch command {
case "serve":
    let rest = Array(args.dropFirst(2))
    let port = rest.first.flatMap(Int.init) ?? 47820
    let name = rest.count > 1 ? rest[1] : "iPad"
    let node = try Node(dataDir: dataDir, name: name) { peer, code in
        log("CODE \(code) shown for \(peer); confirming here")
        return true
    }
    await node.openPairing()
    log("Swift device \(name) (\(await node.id)) listening on \(port), ready to link")
    do {
        try await node.serve(port: port)
    } catch {
        log("Server stopped: \(error)")
        exit(1)
    }

case "status":
    let node = try Node(dataDir: dataDir, name: "") { _, _ in false }
    log("This device: \(await node.id)")
    for d in await node.devices.values.sorted(by: { $0.id < $1.id }) {
        log("Linked: \(d.name) (\(d.id))")
    }

default:
    log("unknown command \(command)")
    exit(2)
}
