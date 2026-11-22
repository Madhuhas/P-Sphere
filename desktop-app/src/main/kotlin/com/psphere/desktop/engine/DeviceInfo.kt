package com.psphere.desktop.engine

import com.psphere.core.node.DriveSpace
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlin.math.PI
import kotlin.math.sin

/** This computer's battery, read in the way each system offers. */
object Battery {
    data class Reading(val percent: Int, val charging: Boolean)

    @Volatile private var cached: Pair<Long, Reading?>? = null

    /** Null on a computer without a battery, or when it can't be read. Cached for 30 seconds. */
    fun read(): Reading? {
        cached?.let { (at, r) -> if (System.currentTimeMillis() - at < 30_000) return r }
        val r = runCatching {
            when (DesktopSystem.os) {
                Os.WINDOWS -> windows()
                Os.LINUX -> linux()
                Os.MAC -> mac()
            }
        }.getOrNull()
        cached = System.currentTimeMillis() to r
        return r
    }

    /** GetSystemPowerStatus: AC line (0/1), battery flags (128 = none), percent (255 = unknown). */
    private fun windows(): Reading? {
        Arena.ofConfined().use { arena ->
            val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)
            val fn = Linker.nativeLinker().downcallHandle(kernel32.find("GetSystemPowerStatus").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
            val s = arena.allocate(12)
            if (fn.invokeWithArguments(s) as Int == 0) return null
            val ac = s.get(JAVA_BYTE, 0).toInt() and 0xff
            val flags = s.get(JAVA_BYTE, 1).toInt() and 0xff
            val percent = s.get(JAVA_BYTE, 2).toInt() and 0xff
            if (flags == 255 || flags and 128 != 0 || percent > 100) return null
            return Reading(percent, ac == 1 || flags and 8 != 0)
        }
    }

    private fun linux(): Reading? {
        val bat = File("/sys/class/power_supply").listFiles()?.firstOrNull { it.name.startsWith("BAT") } ?: return null
        val percent = File(bat, "capacity").readText().trim().toInt()
        val status = runCatching { File(bat, "status").readText().trim() }.getOrDefault("")
        return Reading(percent.coerceIn(0, 100), status == "Charging" || status == "Full")
    }

    /** `pmset -g batt` prints e.g. "-InternalBattery-0 (id=…)	76%; charging; 1:02 remaining". */
    private fun mac(): Reading? {
        val p = ProcessBuilder("pmset", "-g", "batt").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        val m = Regex("""(\d{1,3})%;\s*([a-zA-Z ]+)""").find(out) ?: return null
        val state = m.groupValues[2].trim().lowercase()
        return Reading(m.groupValues[1].toInt().coerceIn(0, 100), state.startsWith("charging") || state == "charged" || "ac attached" in state)
    }
}

/** Free space on this computer's drives. */
object Drives {
    private const val MIN_SIZE = 1L shl 30

    /**
     * The drive holding [main] (where backups go) and, on Windows, the other drives. A virtual
     * drive that reports the same size as one already listed (Google Drive's "G:" mirrors the
     * drive its files are cached on) is left out, so the same space isn't counted twice.
     */
    fun list(main: File): Pair<DriveSpace, List<DriveSpace>> {
        if (DesktopSystem.os != Os.WINDOWS) return DriveSpace("", main.usableSpace, main.totalSpace) to emptyList()
        val path = runCatching { main.canonicalPath }.getOrDefault(main.absolutePath)
        val roots = File.listRoots().filter { runCatching { it.totalSpace >= MIN_SIZE }.getOrDefault(false) }
        val mainRoot = roots.firstOrNull { path.startsWith(it.path, ignoreCase = true) }
        val first = DriveSpace(mainRoot?.let(::nameOf) ?: "", main.usableSpace, main.totalSpace)
        val seenSizes = mutableSetOf(first.totalBytes)
        val others = roots.filter { it != mainRoot }.mapNotNull { r ->
            val total = r.totalSpace
            if (!seenSizes.add(total)) null else DriveSpace(nameOf(r), r.usableSpace, total)
        }
        return first to others
    }

    private fun nameOf(root: File) = root.path.trimEnd('\\', '/')
}

/** The sound this computer plays while another device is looking for it. */
object Ringer {
    private var clip: Clip? = null

    @Synchronized
    fun start() {
        if (clip != null) return
        clip = runCatching {
            val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
            AudioSystem.getClip().apply {
                val pcm = pattern()
                open(format, pcm, 0, pcm.size)
                loop(Clip.LOOP_CONTINUOUSLY)
            }
        }.getOrNull()
        // Without a sound device, at least the system beep.
        if (clip == null) runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
    }

    @Synchronized
    fun stop() {
        clip?.let { runCatching { it.stop(); it.close() } }
        clip = null
    }

    private const val RATE = 44_100

    /** Two rising chimes, then a pause: about 1.3 seconds, played over and over. */
    private fun pattern(): ByteArray {
        val tones = listOf(880.0 to 0.16, 0.0 to 0.06, 1318.5 to 0.22, 0.0 to 0.85)
        val samples = ShortArray(tones.sumOf { (RATE * it.second).toInt() })
        var at = 0
        for ((freq, secs) in tones) {
            val n = (RATE * secs).toInt()
            for (i in 0 until n) {
                // Short fades at both ends, so there are no clicks.
                val fade = minOf(1.0, i / 400.0, (n - i) / 400.0)
                val v = if (freq == 0.0) 0.0 else sin(2 * PI * freq * i / RATE) * 0.8 * fade
                samples[at + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            at += n
        }
        return ByteArray(samples.size * 2).also { b ->
            samples.forEachIndexed { i, s -> b[i * 2] = (s.toInt() and 0xff).toByte(); b[i * 2 + 1] = (s.toInt() shr 8).toByte() }
        }
    }
}
