package com.psphere.core

import com.psphere.core.model.Include
import com.psphere.core.model.Media
import com.psphere.core.model.RuleRec
import com.psphere.core.node.HostPort
import com.psphere.core.node.Node
import com.psphere.core.node.NodeServer
import com.psphere.core.node.Platform
import com.psphere.core.storage.Folder
import com.psphere.core.storage.FileSystemFolder
import java.io.File

/** A simulated device: a Node with its own data folder, server and folders on disk. */
class TestDevice(val name: String, base: File) : AutoCloseable {
    val dir = File(base, name).apply { mkdirs() }

    private val platform = object : Platform {
        override fun openFolder(location: String): Folder? = File(location).takeIf { it.isDirectory }?.let(::FileSystemFolder)
        override fun makePreview(folder: Folder, relPath: String, media: Media) = "preview of $relPath".toByteArray()
    }

    val node = Node(File(dir, "data"), name, platform)
    val server = NodeServer(node).apply { start() }
    val id get() = node.id
    val address get() = HostPort("127.0.0.1", server.localPort)

    /** A folder on this device's "disk". */
    fun folder(name: String) = File(dir, "disk/$name").apply { mkdirs() }

    fun receiveInto(name: String = "Backups"): File = folder(name).also { node.setBackupLocation(it.path) }

    fun backUp(label: String, folder: File, to: List<TestDevice>, include: Include = Include.MEDIA): RuleRec =
        node.addRule(label, folder.path, to.map { it.id }.toSet(), include)

    fun linkTo(other: TestDevice) {
        val offer = other.node.openPairing(listOf("127.0.0.1"), other.server.localPort)
        node.linkWithQr(offer.toUri())
    }

    fun syncWith(other: TestDevice) = node.syncWith(other.id, listOf(other.address))

    override fun close() = server.stop()
}

fun online(vararg devices: TestDevice): Map<String, HostPort> = devices.associate { it.id to it.address }

fun File.write(rel: String, text: String): File = File(this, rel).apply { parentFile.mkdirs(); writeText(text) }
