package com.psphere.core

import com.psphere.core.model.Include
import com.psphere.core.model.Media
import com.psphere.core.node.CodeConfirmer
import com.psphere.core.node.HostPort
import com.psphere.core.node.Node
import com.psphere.core.node.Platform
import com.psphere.core.storage.FileSystemFolder
import com.psphere.core.storage.Folder
import java.io.File

/**
 * A command-line PSphere device for manual testing against the Android app (e.g. on an emulator
 * reached through `adb forward`). State lives in the given folder between runs.
 *
 *   gradlew harness -Pargs="<dir>,init,Laptop"
 *   gradlew harness -Pargs="<dir>,link,127.0.0.1,47820"
 *   gradlew harness -Pargs="<dir>,sync,127.0.0.1,47820"
 *   gradlew harness -Pargs="<dir>,status"
 *   gradlew harness -Pargs="<dir>,pair,<other data dir>,<other name>"   links two devices' data folders directly
 *   gradlew harness -Pargs="<dir>,shelf-text,127.0.0.1,47820,<text>"    puts text on the Shelf and shares it
 *   gradlew harness -Pargs="<dir>,shelf-file,127.0.0.1,47820,<path>"    puts a file on the Shelf and shares it
 *   gradlew harness -Pargs="<dir>,shelf,127.0.0.1,47820"               swaps Shelves and lists them
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val platform = object : Platform {
        override fun openFolder(location: String): Folder? = File(location).takeIf { it.isDirectory }?.let(::FileSystemFolder)
        override fun makePreview(folder: Folder, relPath: String, media: Media) = "preview".toByteArray()
    }
    // HARNESS_DAYS_AGO=8 makes this run's changes look 8 days old (to test "old items" on the Shelf).
    val shift = (System.getenv("HARNESS_DAYS_AGO")?.toLongOrNull() ?: 0) * 24 * 3600 * 1000
    val node = Node(File(dir, "data"), args.getOrElse(2) { "Laptop" }, platform) { System.currentTimeMillis() - shift }
    when (args[1]) {
        "init" -> {
            val backups = File(dir, "Backups").apply { mkdirs() }
            val docs = File(dir, "Documents").apply { mkdirs() }
            File(docs, "notes.txt").writeText("Notes from the laptop\n")
            File(docs, "photo-from-laptop.jpg").writeBytes(ByteArray(40_000) { (it % 251).toByte() })
            node.setBackupLocation(backups.path)
            if (node.ownRules().isEmpty()) node.addRule("Laptop Documents", docs.path, emptySet(), Include.ALL)
            println("Device ${node.deviceName} (${node.id}) ready. Backups go to $backups")
        }
        "link" -> {
            val d = node.linkWithCode(HostPort(args[2], args[3].toInt()), CodeConfirmer { name, code ->
                println("CODE $code shown for $name; confirming here"); true
            })
            // Send this device's folder to everything it's linked with.
            node.ownRules().forEach { (rule, _) -> node.updateRule(rule.ruleId, destinations = node.devices().map { it.id }.toSet()) }
            println("Linked with ${d.name} (${d.id})")
        }
        "sync" -> {
            node.scan().also { println("Scan: ${it.files} files, ${it.changed} changed, errors=${it.errors}") }
            for (d in node.devices()) {
                val r = node.syncWith(d.id, listOf(HostPort(args[2], args[3].toInt())))
                println("Sync with ${r.peerName}: sent=${r.sent} received=${r.received} failed=${r.failed} notes=${r.notes} error=${r.error}")
            }
        }
        "pair" -> {
            // Both devices in this process, linked over local servers: for setting up test data
            // folders (e.g. the desktop app's, at <dataDir>/core) without clicking through linking.
            val other = Node(File(args[2]), args.getOrElse(3) { "Laptop" }, platform)
            val a = com.psphere.core.node.NodeServer(node).apply { start() }
            val b = com.psphere.core.node.NodeServer(other).apply { start() }
            node.linkWithQr(other.openPairing(listOf("127.0.0.1"), b.localPort).toUri())
            other.save(); a.stop(); b.stop()
            println("Linked ${node.deviceName} with ${other.deviceName}")
        }
        "shelf-text", "shelf-file" -> {
            // Stay reachable for a while, like a real device, so the other side can fetch files.
            com.psphere.core.node.NodeServer(node).start()
            val what = args.drop(4).joinToString(",")
            val item = if (args[1] == "shelf-text") node.shelfAddText(what) else File(what).inputStream().use { node.shelfAddFile(File(what).name, it) }
            val reached = node.shelfSync(node.devices().associate { it.id to HostPort(args[2], args[3].toInt()) })
            println("Put ${item?.kind} \"${item?.text}\" on the Shelf; shared with $reached device(s)")
            Thread.sleep((System.getenv("HARNESS_STAY_S")?.toLongOrNull() ?: 20) * 1000)
        }
        "shelf" -> {
            com.psphere.core.node.NodeServer(node).start()
            val reached = node.shelfSync(node.devices().associate { it.id to HostPort(args[2], args[3].toInt()) })
            println("Swapped with $reached device(s). On the Shelf:")
            node.shelfItems().forEach { println("  ${it.originName}: ${it.item.kind} \"${it.item.text}\" ${it.item.size} B pinned=${it.item.pinned} here=${it.here}") }
        }
        "status" -> {
            println("Linked: " + node.devices().joinToString { "${it.name} (${it.id})" })
            println("Library:")
            node.library().forEach { println("  ${it.originName}: ${it.item.relPath} ${it.item.size} B, here=${it.onThisDevice}") }
            println("Kept:")
            node.keptGroups().forEach { println("  ${it.path}: ${it.files} files, ${it.bytes} B") }
        }
    }
    node.save()
    System.exit(0)
}
