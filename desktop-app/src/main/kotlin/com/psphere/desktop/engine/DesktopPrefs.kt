package com.psphere.desktop.engine

import java.io.File
import java.util.Properties

/** Settings only the desktop app has. The shared core keeps everything else. */
class DesktopPrefs(private val file: File) {
    private val props = Properties().apply { if (file.isFile) runCatching { file.inputStream().use { load(it) } } }

    /** The first-start screen was completed. */
    var onboarded: Boolean
        get() = props.getProperty("onboarded") == "1"
        set(v) = put("onboarded", if (v) "1" else "0")

    /** Closing the window leaves PSphere running in the tray. On by default. */
    var keepInTray: Boolean
        get() = props.getProperty("keepInTray") != "0"
        set(v) = put("keepInTray", if (v) "1" else "0")

    /** The "still running in the tray" notice was shown once. */
    var trayNoticeShown: Boolean
        get() = props.getProperty("trayNoticeShown") == "1"
        set(v) = put("trayNoticeShown", if (v) "1" else "0")

    @Synchronized
    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { props.store(it, "PSphere desktop settings") }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
