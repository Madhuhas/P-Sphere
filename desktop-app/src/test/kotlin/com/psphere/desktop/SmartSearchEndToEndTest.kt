package com.psphere.desktop

import com.psphere.core.model.Media
import com.psphere.core.node.HostPort
import com.psphere.core.node.Node
import com.psphere.core.node.NodeServer
import com.psphere.core.node.Platform
import com.psphere.core.node.SmartItem
import com.psphere.core.node.SmartLibrary
import com.psphere.core.node.SmartOps
import com.psphere.core.storage.FileSystemFolder
import com.psphere.core.storage.Folder
import com.psphere.desktop.engine.DesktopPlatform
import com.psphere.desktop.smart.Clip
import com.psphere.desktop.smart.SmartIndex
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The whole path with the real model: the laptop makes previews of its photos, scans them, and
 * a linked phone searches over the network. Skipped unless -Dsmart.models and -Dsmart.photos are given.
 */
class SmartSearchEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aLinkedPhoneSearchesTheLaptopsPhotos() {
        val models = System.getProperty("smart.models")?.let(::File)
        val photos = System.getProperty("smart.photos")?.let(::File)
        assumeTrue(models?.isDirectory == true && photos?.isDirectory == true)

        val laptop = Node(tmp.newFolder("laptop"), "Laptop", DesktopPlatform())
        val phonePlatform = object : Platform {
            override fun openFolder(location: String): Folder? = File(location).takeIf { it.isDirectory }?.let(::FileSystemFolder)
        }
        val phone = Node(tmp.newFolder("phone"), "Phone", phonePlatform)
        val laptopServer = NodeServer(laptop).apply { start() }
        val phoneServer = NodeServer(phone).apply { start() }
        try {
            phone.linkWithQr(laptop.openPairing(listOf("127.0.0.1"), laptopServer.localPort).toUri())
            laptop.addRule("Pictures", photos!!.path, emptySet(), com.psphere.core.model.Include.MEDIA)
            laptop.scan()
            phone.syncWith(laptop.id, listOf(HostPort("127.0.0.1", laptopServer.localPort)))
            assertEquals("the phone sees the laptop's photos", 10, phone.library().size)

            Clip(models!!, threads = 1).use { clip ->
                val index = SmartIndex(tmp.newFile("index.bin").apply { delete() })
                laptop.library().filter { it.item.media != Media.OTHER }.forEach { li ->
                    laptop.previews.get(li.item.hash)?.let { p -> clip.embedImage(p)?.let { index.put(li.item.hash, it) } }
                }
                assertEquals(10, index.size())
                laptop.smart = SmartLibrary { op, args ->
                    if (op != SmartOps.SEARCH) return@SmartLibrary emptyList()
                    val hits = index.search(clip.embedText(args[0]), 100, 0.20f)
                    val best = hits.firstOrNull()?.second ?: return@SmartLibrary emptyList()
                    hits.filter { it.second >= best - 0.05f }.map { SmartItem(SmartItem.Kind.PHOTO, it.first, score = it.second) }
                }
                val online = mapOf(laptop.id to HostPort("127.0.0.1", laptopServer.localPort))
                for ((q, want) in listOf("eiffel tower" to "eiffel.jpg", "a bear" to "bear.jpg", "pizza" to "pizza.jpg", "yellow flower" to "flower.jpg")) {
                    val found = phone.smartAsk(SmartOps.SEARCH, listOf(q), online)
                    val photos = phone.photosOf(found.items)
                    println("'$q' -> ${photos.map { it.item.relPath }} via ${found.from}")
                    assertEquals(listOf("Laptop"), found.from)
                    assertEquals(want, photos.first().item.relPath)
                }
            }
        } finally {
            laptopServer.stop(); phoneServer.stop()
        }
    }
}
