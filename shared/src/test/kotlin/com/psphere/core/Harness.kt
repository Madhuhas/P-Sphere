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
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val platform = object : Platform {
        override fun openFolder(location: String): Folder? = File(location).takeIf { it.isDirectory }?.let(::FileSystemFolder)
        override fun makePreview(folder: Folder, relPath: String, media: Media) = "preview".toByteArray()
    }
    val node = Node(File(dir, "data"), args.getOrElse(2) { "Laptop" }, platform)
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
