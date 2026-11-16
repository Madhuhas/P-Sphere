package com.psphere.desktop

import com.psphere.desktop.smart.SmartModels
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Downloads the real model (about 150 MB). Skipped unless -Dsmart.download=1. */
class SmartDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun downloadStopsResumesAndChecksEveryFile() {
        assumeTrue(System.getProperty("smart.download") == "1")
        val dir = tmp.newFolder("models")
        // Cancel part-way through the first file...
        var seen = 0L
        val stopped = runCatching { SmartModels.download(dir, { seen > 30_000_000 }) { done, _ -> seen = done } }
        assertTrue("cancelled", stopped.exceptionOrNull() is InterruptedException)
        val partial = dir.listFiles()!!.single { it.name.endsWith(".part") }.length()
        println("stopped with ${partial / 1_000_000} MB downloaded")
        // ...then carry on from there.
        var resumedFrom = -1L
        val t = System.nanoTime()
        SmartModels.download(dir, { false }) { done, _ -> if (resumedFrom < 0) resumedFrom = done }
        println("resumed at ${resumedFrom / 1_000_000} MB, finished in ${(System.nanoTime() - t) / 1_000_000_000} s")
        assertTrue("resumed, not restarted", resumedFrom >= partial)
        assertTrue(SmartModels.installed(dir))
    }
}
