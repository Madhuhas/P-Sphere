package com.psphere.core.update

import java.net.HttpURLConnection
import java.net.URI

/**
 * Checks GitHub for a newer release. Together with [UpdateDownload] this is the only part of
 * PSphere that reaches the internet, and it runs only when the user taps "Check for updates" in
 * Settings. No dependencies: one plain HTTPS request, read with a small JSON reader.
 *
 * [check] blocks on the network, so callers run it off the main thread.
 */
object UpdateCheck {
    const val OWNER_REPO = "Madhuhas/P-Sphere"
    const val RELEASES_API = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=10"
    const val RELEASES_PAGE = "https://github.com/$OWNER_REPO/releases"
    private const val TIMEOUT_MS = 10_000

    /**
     * Where releases are read from. Only ever changed for testing against a local copy of a
     * release (the apps set it from a developer setting); [isTesting] then allows plain http.
     */
    @Volatile var apiUrl: String = RELEASES_API
    val isTesting: Boolean get() = apiUrl != RELEASES_API

    /** One file attached to a release, with the SHA-256 GitHub lists for it (null if it lists none). */
    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    sealed interface Status {
        /** No newer release than [current]. */
        data class UpToDate(val current: String) : Status
        /**
         * A newer release exists; [url] is its page. [version] is display form, e.g. "2.1".
         * [notes] are the release notes as written on GitHub, [assets] its files.
         */
        data class Available(val version: String, val url: String, val notes: String = "", val assets: List<Asset> = emptyList()) : Status {
            /** The file for this kind of device (".apk", ".msi"), if it can be installed with one click. */
            fun assetEndingWith(suffix: String): Asset? =
                assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) && it.sha256 != null }
        }
        /** The check couldn't complete (offline, rate-limited, unexpected reply). */
        data class Failed(val reason: String) : Status
    }

    /** Contacts GitHub once and compares the newest release with [currentVersion]. Blocking. */
    fun check(currentVersion: String): Status {
        val body = try {
            fetch(apiUrl)
        } catch (_: Exception) {
            return Status.Failed("Couldn't reach GitHub. Check your internet connection and try again.")
        }
        val release = newestRelease(body) ?: return Status.Failed("Couldn't read the latest version from GitHub.")
        return if (isNewer(release.tag, currentVersion)) {
            Status.Available(display(release.tag), release.page ?: "$RELEASES_PAGE/tag/${release.tag}", release.notes, release.assets)
        } else {
            Status.UpToDate(display(currentVersion))
        }
    }

    internal data class Release(val tag: String, val page: String?, val notes: String, val assets: List<Asset>)

    /** The first release in GitHub's list is the newest (drafts are never included; pre-releases are). */
    internal fun newestRelease(json: String): Release? {
        val list = runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: return null
        val r = list.firstOrNull() as? Map<*, *> ?: return null
        val tag = r["tag_name"] as? String ?: return null
        val assets = (r["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            a as? Map<*, *> ?: return@mapNotNull null
            val name = a["name"] as? String ?: return@mapNotNull null
            val url = a["browser_download_url"] as? String ?: return@mapNotNull null
            val size = (a["size"] as? Number)?.toLong() ?: return@mapNotNull null
            val sha = (a["digest"] as? String)?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            Asset(name, url, size, sha)
        }
        return Release(tag, r["html_url"] as? String, (r["body"] as? String).orEmpty(), assets)
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

    /** The newest release's tag, or null. */
    internal fun latestTag(json: String): String? = newestRelease(json)?.tag

    /**
     * Release notes as plain text for a small "What's new" box: Markdown headings, bold and link
     * syntax removed, checksum tables and the like left out, at most [max] characters.
     */
    fun plainNotes(notes: String, max: Int = 700): String {
        val lines = notes.replace("\r", "").lines()
            .takeWhile { !it.trim().startsWith("SHA-256", ignoreCase = true) && !it.trim().startsWith("| ") && !it.trim().startsWith("```") }
            .map { l ->
                l.trim()
                    .replace(Regex("""^#+\s*"""), "")
                    .replace(Regex("""^[*-]\s+"""), "• ")
                    .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                    .replace("**", "").replace("`", "")
            }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= max) text else text.take(max).substringBeforeLast('\n').trimEnd() + "\n…"
    }

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
