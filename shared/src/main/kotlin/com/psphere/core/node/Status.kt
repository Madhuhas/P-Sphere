package com.psphere.core.node

import com.psphere.core.replaceFile
import com.psphere.core.savedFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * How a device is doing: its battery and free space. Devices swap these while they're on the
 * same Wi-Fi, and keep the last one they saw from each other, so an offline device still shows
 * its last known values.
 */
data class DeviceStatus(
    /** 0 to 100, or -1 when the device has no battery (a desktop PC) or can't tell. */
    val battery: Int,
    val charging: Boolean,
    /** Free space where backups go (or the main drive), -1 if unknown. */
    val freeBytes: Long,
    val totalBytes: Long,
    /** When this device received it. Not sent over the wire. */
    val at: Long = 0,
    /** The drive [freeBytes] is about, e.g. "K:" (empty on a phone). */
    val driveName: String = "",
    /** A computer's other drives. */
    val otherDrives: List<DriveSpace> = emptyList(),
) {
    val hasBattery: Boolean get() = battery in 0..100
    val hasSpace: Boolean get() = freeBytes >= 0 && totalBytes > 0

    companion object {
        val UNKNOWN = DeviceStatus(-1, false, -1, -1)
    }
}

data class DriveSpace(val name: String, val freeBytes: Long, val totalBytes: Long)

/** What happened when this device asked another to ring. */
enum class RingResult {
    /** It's ringing (or, for a stop, it stopped). */
    OK,
    /** That device has ringing turned off in its settings. */
    TURNED_OFF,
    /** It answered, but it's an older version that can't ring. */
    NEEDS_UPDATE,
    /** It couldn't be reached. */
    UNREACHABLE,
}

/** The last status seen from each linked device, kept across restarts. */
internal class StatusStore(private val file: File) {
    private val map = HashMap<String, DeviceStatus>()

    init {
        runCatching {
            savedFile(file)?.let { f ->
                DataInputStream(f.inputStream().buffered()).use { i ->
                    if (i.readInt() != FORMAT) return@use
                    repeat(i.readInt()) {
                        val id = i.readUTF()
                        val s = DeviceStatus(i.readInt(), i.readBoolean(), i.readLong(), i.readLong(), i.readLong(), i.readUTF())
                        map[id] = s.copy(otherDrives = List(i.readInt()) { DriveSpace(i.readUTF(), i.readLong(), i.readLong()) })
                    }
                }
            }
        }
    }

    @Synchronized fun get(id: String): DeviceStatus? = map[id]

    @Synchronized fun put(id: String, s: DeviceStatus) { map[id] = s; save() }

    @Synchronized fun remove(id: String) { if (map.remove(id) != null) save() }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(FORMAT)
                o.writeInt(map.size)
                map.forEach { (id, s) ->
                    o.writeUTF(id); o.writeInt(s.battery); o.writeBoolean(s.charging)
                    o.writeLong(s.freeBytes); o.writeLong(s.totalBytes); o.writeLong(s.at); o.writeUTF(s.driveName)
                    o.writeInt(s.otherDrives.size)
                    s.otherDrives.forEach { d -> o.writeUTF(d.name); o.writeLong(d.freeBytes); o.writeLong(d.totalBytes) }
                }
            }
            replaceFile(tmp, file)
        }
    }

    private companion object { const val FORMAT = 2 }
}
