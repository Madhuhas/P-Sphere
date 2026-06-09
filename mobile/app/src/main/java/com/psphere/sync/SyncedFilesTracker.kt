package com.psphere.sync

import android.content.Context
import java.io.File

/**
 * Tracks absolute paths of files that were successfully sent to at least one peer.
 * Used by "Free up space" to safely delete local copies that are confirmed on another device.
 *
 * Storage: a plain newline-delimited text file in the app's private files dir.
 * Only non-media-store originals (files the user explicitly chose to back up) are ever deleted —
 * we never delete files from DCIM or Pictures that the user hasn't opted into freeing.
 */
object SyncedFilesTracker {

    private const val TRACK_FILE = "synced_files.txt"

    // Single lock object for all file operations on TRACK_FILE
    private val lock = Any()

    @Synchronized
    fun record(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        synchronized(lock) {
            File(context.filesDir, TRACK_FILE)
                .appendText(paths.joinToString("\n") + "\n")
        }
    }

    /** Returns paths that were synced AND still exist locally. */
    fun getFreeablePaths(context: Context): List<String> {
        val f = File(context.filesDir, TRACK_FILE)
        if (!f.exists()) return emptyList()
        return synchronized(lock) {
            f.readLines()
                .filter { it.isNotBlank() }
                .distinct()
                .filter { File(it).exists() }
        }
    }

    /** Total bytes that can be freed. */
    fun getFreeableBytes(context: Context): Long =
        getFreeablePaths(context).sumOf { File(it).length() }

    /**
     * Deletes all tracked synced files from local storage.
     * Returns the number of files successfully deleted.
     */
    fun deleteAll(context: Context): Int {
        return synchronized(lock) {
            val paths = getFreeablePaths(context)
            var deleted = 0
            for (path in paths) {
                if (File(path).delete()) deleted++
            }
            File(context.filesDir, TRACK_FILE).delete()
            deleted
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.filesDir, TRACK_FILE).delete()
        }
    }
}
