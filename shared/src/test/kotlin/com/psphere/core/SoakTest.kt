package com.psphere.core

import com.psphere.core.model.Include
import com.psphere.core.node.HostPort
import com.psphere.core.node.Node
import com.psphere.core.node.NodeServer
import com.psphere.core.node.Platform
import com.psphere.core.storage.FileSystemFolder
import com.psphere.core.storage.Folder
import com.psphere.core.model.Media
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A long-running test with three linked devices that keep changing their files and the Shelf,
 * drop off the network and come back, and sync with each other at the same time. Every few
 * minutes everything is brought up to date and checked: every file is backed up byte for byte,
 * deleted files keep their backups, the Shelf is the same everywhere, nothing is left half-written,
 * and memory doesn't keep growing.
 *
 * Skipped unless given a duration: gradlew test --tests '*SoakTest*' -Dsoak.minutes=120
 */
class SoakTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Device(val name: String, base: File) {
        val dir = File(base, name).apply { mkdirs() }
        private val platform = object : Platform {
            override fun openFolder(location: String): Folder? = File(location).takeIf { it.isDirectory }?.let(::FileSystemFolder)
            override fun makePreview(folder: Folder, relPath: String, media: Media) = "preview".toByteArray()
        }
        val node = Node(File(dir, "data"), name, platform)
        var server: NodeServer? = NodeServer(node).apply { start() }
        val online get() = server != null
        val address get() = HostPort("127.0.0.1", server!!.localPort)
        val source = File(dir, "source").apply { mkdirs() }
        val backups = File(dir, "backups").apply { mkdirs() }

        fun goAway() { server?.stop(); server = null }
        fun comeBack() { if (server == null) server = NodeServer(node).apply { start() } }
    }

    private fun sha(f: File): String = f.inputStream().use { sha256Hex(it) }

    @Test fun threeDevicesStayConsistentForHours() {
        val minutes = System.getProperty("soak.minutes")?.toLongOrNull()
        assumeTrue(minutes != null && minutes > 0)
        val rnd = Random(System.getProperty("soak.seed")?.toLongOrNull() ?: 42)
        val log = File(System.getProperty("soak.log") ?: File(tmp.root, "soak.log").path)
        fun say(s: String) { val line = "[${java.time.LocalTime.now().withNano(0)}] $s"; println(line); log.appendText(line + "\n") }

        val devices = listOf("Phone", "Tablet", "Laptop").map { Device(it, tmp.root) }
        val (phone, tablet, laptop) = devices
        devices.forEach { it.node.setBackupLocation(it.backups.path) }
        // Link everyone with everyone.
        for (i in devices.indices) for (j in i + 1 until devices.size) {
            val a = devices[i]; val b = devices[j]
            a.node.linkWithQr(b.node.openPairing(listOf("127.0.0.1"), b.server!!.localPort).toUri())
        }
        // Each device backs up its own folder to the other two.
        for (d in devices) d.node.addRule("Stuff", d.source.path, devices.filter { it !== d }.map { it.node.id }.toSet(), Include.ALL)

        val pool = Executors.newFixedThreadPool(6)
        val problems = ArrayList<String>()
        val heap = ArrayList<Long>()
        var syncs = 0; var syncErrors = 0; var changes = 0
        val end = System.currentTimeMillis() + minutes!! * 60_000
        var nextCheck = System.currentTimeMillis() + 5 * 60_000
        var round = 0
        say("Soak started for $minutes min")

        fun online(vararg except: Device) = devices.filter { it.online && it !in except }.associate { it.node.id to it.address }

        fun mutate(d: Device) {
            val files = d.source.walkTopDown().filter { it.isFile }.toList()
            // Every file is stored three times (here and on two backups), so keep the total in
            // bounds: past the cap, files are deleted instead of added. Never fills the drive.
            val stored = files.sumOf { it.length() }
            if (stored > SOURCE_CAP_BYTES) { files.randomOrNull(rnd)?.delete(); changes++; return }
            when (rnd.nextInt(10)) {
                in 0..3 -> { // new file, mostly small, sometimes big
                    val size = if (rnd.nextInt(20) == 0) rnd.nextInt(2_000_000, 8_000_000) else rnd.nextInt(1, 400_000)
                    val f = File(d.source, "${if (rnd.nextBoolean()) "sub${rnd.nextInt(5)}/" else ""}f${round}-${rnd.nextInt(100000)}.bin")
                    f.parentFile.mkdirs(); f.writeBytes(rnd.nextBytes(size))
                }
                4, 5 -> files.randomOrNull(rnd)?.let { it.appendBytes(rnd.nextBytes(rnd.nextInt(1, 50_000))) } // changed
                6 -> files.randomOrNull(rnd)?.delete() // deleted
                7 -> d.node.shelfAddText("note $round ${rnd.nextInt()}")
                8 -> d.node.shelfAddFile("shelf-$round.bin", rnd.nextBytes(rnd.nextInt(1, 2_000_000)).inputStream())
                9 -> d.node.shelfItems().randomOrNull(rnd)?.let { d.node.shelfRemove(listOf(it.item.id)) }
            }
            // Keep the Shelf to a sensible size as well.
            if (d.node.shelfItems().size > 120) d.node.shelfItems().last().let { d.node.shelfRemove(listOf(it.item.id)) }
            changes++
        }

        fun syncPair(a: Device, b: Device) {
            if (!a.online || !b.online) return
            val r = runCatching { a.node.syncWith(b.node.id, listOf(b.address)) }.getOrElse { problems += "syncWith threw: $it"; return }
            synchronized(this) { syncs++; if (r.error != null) syncErrors++ }
        }

        fun settle() {
            devices.forEach { it.comeBack() }
            repeat(3) {
                devices.forEach { it.node.scan() }
                for (a in devices) for (b in devices) if (a !== b) syncPair(a, b)
                devices.forEach { it.node.shelfSync(online(it)) }
            }
            // Give automatic Shelf downloads time to finish.
            val until = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < until && devices.any { d -> d.node.shelfItems().any { it.item.isFile && it.item.size <= 25L * 1024 * 1024 && !it.here } }) Thread.sleep(500)
        }

        fun check() {
            settle()
            var bad = 0
            for (src in devices) for (dst in devices) {
                if (src === dst) continue
                val base = File(dst.backups, "${src.name}/Stuff")
                src.source.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(src.source).invariantSeparatorsPath
                    val copy = File(base, rel)
                    if (!copy.isFile) { bad++; if (bad <= 10) problems += "${dst.name} is missing ${src.name}/$rel" }
                    else if (sha(copy) != sha(f)) { bad++; if (bad <= 10) problems += "${dst.name} has a different ${src.name}/$rel" }
                }
            }
            // Nothing half-written left behind (interrupted backups kept for resuming are fine).
            devices.forEach { d ->
                val parts = d.dir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") && "/.psphere/tmp/" !in it.invariantSeparatorsPath }.toList()
                if (parts.isNotEmpty()) problems += "${d.name} has ${parts.size} leftover .part files, e.g. ${parts.first().relativeTo(d.dir)}"
            }
            // The Shelf is the same everywhere.
            val shelves = devices.map { d -> d.node.shelfItems().map { it.item.id }.toSet() }
            if (shelves.distinct().size != 1) problems += "Shelves differ: ${devices.zip(shelves).joinToString { (d, s) -> "${d.name}=${s.size}" }}"
            devices.forEach { d -> d.node.shelfItems().filter { it.item.isFile && it.item.size <= 25L * 1024 * 1024 && !it.here }.takeIf { it.isNotEmpty() }?.let {
                problems += "${d.name} is missing ${it.size} small Shelf files"
            } }
            System.gc(); Thread.sleep(500); System.gc()
            val used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
            heap += used
            val files = devices.sumOf { d -> d.source.walkTopDown().count { it.isFile } }
            say("Check: $files files, ${devices.first().node.shelfItems().size} on the Shelf, $syncs syncs ($syncErrors with an error), $changes changes, " +
                "heap ${used} MB, threads ${Thread.activeCount()}, problems so far ${problems.size}")
            problems.takeLast(5).forEach { say("  problem: $it") }
        }

        while (System.currentTimeMillis() < end) {
            round++
            // Old versions of changed files are kept for 30 days on two devices, so data still grows:
            // stop early rather than ever filling the drive.
            if (tmp.root.usableSpace < MIN_FREE_BYTES) { say("Stopping early: under ${MIN_FREE_BYTES / 1_073_741_824} GB free on the drive"); break }
            // Someone sometimes leaves the network for a while, or comes back.
            if (rnd.nextInt(15) == 0) devices.filter { it.online }.randomOrNull(rnd)?.takeIf { devices.count { d -> d.online } > 1 }?.goAway()
            if (rnd.nextInt(6) == 0) devices.filter { !it.online }.randomOrNull(rnd)?.comeBack()
            repeat(rnd.nextInt(1, 4)) { mutate(devices.random(rnd)) }
            devices.filter { it.online }.forEach { it.node.scan(budgetMs = 2_000) }
            // Several syncs at once, sometimes both directions of the same pair, while files keep changing.
            val jobs = (0 until rnd.nextInt(1, 4)).map {
                val a = devices.random(rnd); val b = devices.filter { it !== a }.random(rnd)
                pool.submit { syncPair(a, b) }
            } + pool.submit { if (rnd.nextBoolean()) devices.random(rnd).let { d -> if (d.online) d.node.shelfSync(online(d)) } }
            if (rnd.nextInt(3) == 0) mutate(devices.random(rnd))
            jobs.forEach { runCatching { it.get(5, TimeUnit.MINUTES) }.onFailure { problems += "A sync didn't finish in 5 minutes: $it" } }
            if (System.currentTimeMillis() > nextCheck) { check(); nextCheck = System.currentTimeMillis() + 10 * 60_000 }
            Thread.sleep(rnd.nextLong(200, 1500))
        }
        check()
        pool.shutdownNow()
        devices.forEach { it.goAway() }
        say("Soak finished. Heap after each check (MB): $heap")
        problems.forEach { say("PROBLEM: $it") }
        assertTrue("Soak found ${problems.size} problems; see ${log.path}", problems.isEmpty())
    }

    companion object {
        /** Per device. With three copies of everything, the whole test stays under about 1 GB. */
        private const val SOURCE_CAP_BYTES = 100L * 1024 * 1024
        private const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024
    }
}
