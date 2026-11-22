package com.psphere.core

import com.psphere.core.model.Include
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * A big library: many files and a few large ones. Times the first scan and backup, then a rescan
 * and a sync with nothing new, and checks memory. Skipped unless given: -Dbig.files=20000
 */
class BigLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aBigLibraryBacksUpAndStaysQuickAfterwards() {
        val n = System.getProperty("big.files")?.toIntOrNull()
        assumeTrue(n != null && n > 0)
        val rnd = Random(1)
        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
        fun heapMb(): Long { System.gc(); Thread.sleep(200); return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576 }

        TestDevice("Phone", tmp.root).use { phone ->
            TestDevice("Laptop", tmp.root).use { laptop ->
                phone.linkTo(laptop)
                val backups = laptop.receiveInto()
                val camera = phone.folder("Camera")
                repeat(n!!) { i -> File(camera, "y${i % 7}/m${i % 12}/IMG_$i.jpg").apply { parentFile.mkdirs(); writeBytes(rnd.nextBytes(rnd.nextInt(2_000, 20_000))) } }
                repeat(3) { i -> File(camera, "VID_$i.mp4").writeBytes(rnd.nextBytes(150_000_000)) }
                phone.backUp("Camera", camera, listOf(laptop), Include.ALL)

                var t = System.nanoTime()
                val scan = phone.node.scan()
                val firstScan = ms(t)
                t = System.nanoTime()
                val first = phone.syncWith(laptop)
                val firstSync = ms(t)
                t = System.nanoTime()
                phone.node.scan()
                val rescan = ms(t)
                t = System.nanoTime()
                val again = phone.syncWith(laptop)
                val idleSync = ms(t)
                t = System.nanoTime()
                val library = phone.node.library()
                val libraryMs = ms(t)
                val heap = heapMb()

                println("Big library: ${scan.files} files. First scan ${firstScan} ms, first backup ${firstSync} ms (sent ${first.sent}), " +
                    "rescan ${rescan} ms, sync with nothing new ${idleSync} ms (sent ${again.sent}), library ${library.size} items in ${libraryMs} ms, heap ${heap} MB")
                assertEquals(n + 3, scan.files)
                assertEquals(null, first.error)
                assertEquals(n + 3, first.sent)
                assertEquals(0, again.sent)
                assertEquals(n + 3, backups.walkTopDown().count { it.isFile && "/.psphere/" !in it.invariantSeparatorsPath })
                assertTrue("a rescan with nothing new should be quick, took $rescan ms", rescan < 30_000)
                assertTrue("a sync with nothing new should be quick, took $idleSync ms", idleSync < 30_000)
            }
        }
    }
}
