package com.psphere.core.node

import com.psphere.core.identity.Fingerprint
import com.psphere.core.net.Codec.str
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class LinkedDevice(
    val id: String,
    val name: String,
    val fingerprint: Fingerprint,
    val linkedAt: Long,
    /** Last address the device was reached at; tried when discovery finds nothing. */
    val lastHost: String? = null,
    val lastPort: Int = 0,
    /** When a sync with this device last completed (0 = never). */
    val lastSyncAt: Long = 0,
)

/** Settings for one of this device's backup folders that stay on this device (not replicated). */
data class LocalRule(
    val ruleId: String,
    /** Platform-specific folder location: a path on Windows, a tree URI on Android. */
    val location: String,
    /** Free up space only once every destination has a copy (otherwise one copy is enough). */
    val freeNeedsAllDestinations: Boolean = false,
    /** Free up space never deletes files taken in the last N days. 0 = free everything backed up. */
    val keepRecentDays: Int = 0,
)

/** Everything about this device that isn't in the replicated catalog. Saved as one small file. */
class LocalState(
    var deviceName: String,
    var backupLocation: String? = null,
    var reserveBytes: Long = com.psphere.core.storage.KeptStore.DEFAULT_RESERVE,
    var receivingPaused: Boolean = false,
    val devices: MutableMap<String, LinkedDevice> = LinkedHashMap(),
    val rules: MutableMap<String, LocalRule> = LinkedHashMap(),
) {
    @Synchronized
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { o ->
            o.writeInt(FORMAT)
            o.str(deviceName)
            o.str(backupLocation ?: "")
            o.writeLong(reserveBytes)
            o.writeBoolean(receivingPaused)
            o.writeInt(devices.size)
            devices.values.forEach { d ->
                o.str(d.id); o.str(d.name); o.str(d.fingerprint.hex); o.writeLong(d.linkedAt)
                o.str(d.lastHost ?: ""); o.writeInt(d.lastPort); o.writeLong(d.lastSyncAt)
            }
            o.writeInt(rules.size)
            rules.values.forEach { r ->
                o.str(r.ruleId); o.str(r.location); o.writeBoolean(r.freeNeedsAllDestinations); o.writeInt(r.keepRecentDays)
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) error("Couldn't save ${file.name}") }
    }

    companion object {
        private const val FORMAT = 1

        fun load(file: File, defaultName: String): LocalState {
            if (!file.isFile) return LocalState(defaultName)
            DataInputStream(BufferedInputStream(file.inputStream())).use { i ->
                require(i.readInt() == FORMAT) { "Unknown settings format" }
                val s = LocalState(i.str(), i.str().ifEmpty { null }, i.readLong(), i.readBoolean())
                repeat(i.readInt()) {
                    val d = LinkedDevice(i.str(), i.str(), Fingerprint.parseHex(i.str())!!, i.readLong(), i.str().ifEmpty { null }, i.readInt(), i.readLong())
                    s.devices[d.id] = d
                }
                repeat(i.readInt()) {
                    val r = LocalRule(i.str(), i.str(), i.readBoolean(), i.readInt())
                    s.rules[r.ruleId] = r
                }
                return s
            }
        }
    }
}
