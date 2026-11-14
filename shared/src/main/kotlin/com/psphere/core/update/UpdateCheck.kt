package com.psphere.core.update

import java.net.HttpURLConnection
import java.net.URI

/**
 * Checks GitHub for a newer release. This is the only part of PSphere that reaches the internet,
 * and it runs only when the user taps "Check for updates" in Settings. No dependencies: it makes
 * one plain HTTPS request and reads just the newest release tag out of the response.
 *
 * [check] blocks on the network, so callers run it off the main thread.
 */
object UpdateCheck {
    const val OWNER_REPO = "Madhuhas/P-Sphere"
    const val RELEASES_API = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=10"
    const val RELEASES_PAGE = "https://github.com/$OWNER_REPO/releases"
    private const val TIMEOUT_MS = 10_000

    sealed interface Status {
        /** No newer release than [current]. */
        data class UpToDate(val current: String) : Status
        /** A newer release exists; [url] is its page. [version] is display form, e.g. "2.1". */
        data class Available(val version: String, val url: String) : Status
        /** The check couldn't complete (offline, rate-limited, unexpected reply). */
        data class Failed(val reason: String) : Status
    }

    /** Contacts GitHub once and compares the newest release tag with [currentVersion]. Blocking. */
    fun check(currentVersion: String): Status {
        val body = try {
            fetch(RELEASES_API)
        } catch (_: Exception) {
            return Status.Failed("Couldn't reach GitHub. Check your internet connection and try again.")
        }
        val latest = latestTag(body) ?: return Status.Failed("Couldn't read the latest version from GitHub.")
        return if (isNewer(latest, currentVersion)) {
            Status.Available(display(latest), "$RELEASES_PAGE/tag/$latest")
        } else {
            Status.UpToDate(display(currentVersion))
        }
    }

    private fun fetch(url: String): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // GitHub requires a User-Agent; Accept pins the API version.
            setRequestProperty("User-Agent", "PSphere-update-check")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The first "tag_name" in the releases array is the newest release — GitHub returns them
     * newest first, and an unauthenticated request never includes drafts (pre-releases are kept).
     */
    internal fun latestTag(json: String): String? =
        Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    /** True if release tag [latest] is a higher version than [current]. Tolerates "v2.1", "2.0", "2.0.0". */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Splits a version into numeric components, ignoring a leading "v" and any suffix like "-beta". */
    private fun parts(v: String): List<Int> =
        v.trimStart('v', 'V').split('.', '-', '+').mapNotNull { seg ->
            seg.takeWhile(Char::isDigit).toIntOrNull()
        }

    private fun display(v: String): String = v.trimStart('v', 'V')
}
