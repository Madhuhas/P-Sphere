package com.psphere.core

import com.psphere.core.storage.FileSystemFolder
import com.psphere.core.storage.Incoming
import com.psphere.core.storage.KeptStore
import com.psphere.core.storage.ReceiveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class HousekeepingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 24L * 3600 * 1000
    private var now = 1_800_000_000_000L

    private fun store(dir: File) = KeptStore(FileSystemFolder(dir), reserveBytes = 0, now = { now })

    private fun incoming(bytes: ByteArray, rel: String = "a.txt") =
        Incoming(sha256Hex(bytes.inputStream()), bytes.size.toLong(), "phone-id", "Phone", "r1", "Camera", rel)

    @Test fun replacedVersionsAreDeletedAfter30Days() {
        val dir = tmp.newFolder("backups")
        val s = store(dir)
        val v1 = "first".toByteArray(); val v2 = "second".toByteArray()
        assertTrue(s.receive(incoming(v1), 0, v1.size.toLong(), v1.inputStream()) is ReceiveResult.Stored)
        assertTrue(s.receive(incoming(v2), 0, v2.size.toLong(), v2.inputStream()) is ReceiveResult.Stored)
        val old = File(dir, ".psphere/old").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, old.size)

        now += 29 * day; s.purgeExpired()
        assertTrue("kept for 30 days", old.single().isFile)
        now += 2 * day; s.purgeExpired()
        assertFalse("gone after 30 days", old.single().exists())
        assertEquals("second", File(dir, "Phone/Camera/a.txt").readText())
    }

    @Test fun abandonedPartialDownloadsAreDeletedAfterAWeekEvenAfterARestart() {
        val dir = tmp.newFolder("backups")
        val big = ByteArray(200_000) { (it % 97).toByte() }
        val meta = incoming(big, "video.mp4")
        // The connection drops halfway through.
        val broken = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100_000) big[n - 1].toInt() and 0xff else throw IOException("connection lost")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= 100_000) throw IOException("connection lost")
                val k = minOf(len, 100_000 - n); System.arraycopy(big, n, b, off, k); n += k; return k
            }
        }
        val s = store(dir)
        runCatching { s.receive(meta, 0, big.size.toLong(), broken) }
        s.flush()
        assertTrue(s.partialLength(meta.hash) > 0)

        val again = store(dir) // after a restart
        now += 3 * day; again.purgeExpired()
        assertTrue("still resumable after 3 days", again.partialLength(meta.hash) > 0)
        now += 5 * day; again.purgeExpired()
        assertEquals("deleted after a week", 0L, again.partialLength(meta.hash))
    }

    @Test fun backupsAndOwnFilesWithoutAPreviewGetOneOnTheNextScan() {
        TestDevice("Phone", tmp.root).use { phone ->
            TestDevice("Laptop", tmp.root).use { laptop ->
                phone.linkTo(laptop)
                laptop.receiveInto()
                val camera = phone.folder("Camera").apply { write("IMG_1.jpg", "photo bytes") }
                phone.backUp("Camera", camera, listOf(laptop))
                phone.node.scan()
                phone.syncWith(laptop)
                val hash = phone.node.catalog.items(phone.id).single().hash
                assertTrue(laptop.node.previews.has(hash))

                // As if both were backed up by a version that didn't make previews.
                laptop.node.previews.remove(hash)
                phone.node.previews.remove(hash)
                laptop.node.scan()
                phone.node.scan()
                assertTrue("the laptop makes its own from the backup it keeps", laptop.node.previews.has(hash))
                assertTrue("the phone makes one for its unchanged file", phone.node.previews.has(hash))
            }
        }
    }
}
