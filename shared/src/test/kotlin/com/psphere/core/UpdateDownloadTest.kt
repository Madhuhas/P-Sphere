package com.psphere.core

import com.psphere.core.update.UpdateCheck
import com.psphere.core.update.UpdateDownload
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest

class UpdateDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/PSphere-2.6.0.apk") { ex ->
            ex.sendResponseHeaders(200, apk.size.toLong()); ex.responseBody.use { it.write(apk) }
        }
        server.createContext("/releases") { ex ->
            val body = releasesJson().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        UpdateCheck.apiUrl = "$base/releases"
    }

    @After fun stop() {
        UpdateCheck.apiUrl = UpdateCheck.RELEASES_API
        server.stop(0)
    }

    private fun releasesJson() = """
        [{"tag_name":"v2.6","html_url":"https://example.org/v2.6","body":"## What's new\r\n- **One-click** updates\r\n- See [docs](https://x)\r\n\r\nSHA-256\r\n| a | b |",
          "assets":[
            {"name":"PSphere-2.6.0.apk","size":${apk.size},"digest":"sha256:$sha","browser_download_url":"$base/PSphere-2.6.0.apk","uploader":{"login":"x"}},
            {"name":"PSphere-2.6.0.msi","size":5,"digest":null,"browser_download_url":"$base/none.msi"}
          ]},
         {"tag_name":"v2.5.1","assets":[]}]
    """.trimIndent()

    private fun asset(sha256: String? = sha) = UpdateCheck.Asset("PSphere-2.6.0.apk", "$base/PSphere-2.6.0.apk", apk.size.toLong(), sha256)

    @Test fun theNewestReleaseIsReadWithItsFilesAndChecksums() {
        val s = UpdateCheck.check("2.5.1") as UpdateCheck.Status.Available
        assertEquals("2.6", s.version)
        assertEquals("https://example.org/v2.6", s.url)
        assertEquals(sha, s.assetEndingWith(".apk")?.sha256)
        // A file without a listed checksum can't be installed with one click.
        assertNull(s.assetEndingWith(".msi"))
        assertEquals("What's new\n• One-click updates\n• See docs", UpdateCheck.plainNotes(s.notes))
        assertTrue(UpdateCheck.check("2.6") is UpdateCheck.Status.UpToDate)
    }

    @Test fun aDownloadIsCheckedAndSaved() {
        var last = 0L
        val f = UpdateDownload.download(asset(), tmp.root, progress = { done, _ -> last = done })
        assertEquals(apk.size.toLong(), last)
        assertTrue(apk.contentEquals(f.readBytes()))
        assertFalse(java.io.File(tmp.root, "PSphere-2.6.0.apk.part").exists())
        // A second time it's reused without downloading.
        server.removeContext("/PSphere-2.6.0.apk")
        assertEquals(f, UpdateDownload.download(asset(), tmp.root))
    }

    @Test fun aDownloadThatDoesntMatchIsThrownAway() {
        try {
            UpdateDownload.download(asset("0".repeat(64)), tmp.root); fail()
        } catch (e: IOException) {
            assertTrue(e.message!!, "didn't match" in e.message!!)
        }
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun aDownloadCanBeCancelled() {
        try {
            UpdateDownload.download(asset(), tmp.root, cancelled = { true }); fail()
        } catch (_: UpdateDownload.Cancelled) {}
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun noChecksumMeansNoDownload() {
        try { UpdateDownload.download(asset(null), tmp.root); fail() } catch (e: IOException) { assertNotNull(e.message) }
    }

    @Test fun plainHttpIsRefusedOutsideTesting() {
        UpdateCheck.apiUrl = UpdateCheck.RELEASES_API
        try { UpdateDownload.download(asset(), tmp.root); fail() } catch (e: IOException) { assertTrue("http" in e.message!!) }
    }

    @Test fun oddFileNamesAreRefused() {
        try {
            UpdateDownload.download(UpdateCheck.Asset("../evil.apk", "$base/x", 1, sha), tmp.root); fail()
        } catch (e: IOException) { assertTrue("name" in e.message!!) }
    }
}
