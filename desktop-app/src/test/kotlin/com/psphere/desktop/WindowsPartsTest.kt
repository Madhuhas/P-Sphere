package com.psphere.desktop

import com.psphere.core.model.Media
import com.psphere.core.storage.FileInfo
import com.psphere.core.storage.FileSystemFolder
import com.psphere.desktop.engine.DesktopPrefs
import com.psphere.desktop.engine.Discovery
import com.psphere.desktop.engine.SingleInstance
import com.psphere.desktop.engine.WindowsPlatform
import com.psphere.desktop.engine.WindowsSystem
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsPartsTest {
    private fun tempDir(): File = Files.createTempDirectory("psphere-test").toFile().apply { deleteOnExit() }

    @Test
    fun foldersOverlapWhenTheSameOrOneInsideTheOther() {
        val root = tempDir()
        val photos = File(root, "Photos").apply { mkdirs() }
        val inside = File(photos, "PSphere").apply { mkdirs() }
        val other = File(root, "Photos 2").apply { mkdirs() }
        assertTrue(WindowsSystem.overlaps(photos.path, photos.path))
        assertTrue(WindowsSystem.overlaps(photos.path, inside.path))
        assertTrue(WindowsSystem.overlaps(inside.path, photos.path))
        // Windows paths ignore case.
        assertTrue(WindowsSystem.overlaps(photos.path.uppercase(), inside.path))
        // A folder whose name only starts the same way is a different folder.
        assertFalse(WindowsSystem.overlaps(photos.path, other.path))
    }

    @Test
    fun deviceIdComesFromTheServiceName() {
        assertEquals("ab12cd34", Discovery.idOf("PSphere-ab12cd34"))
        // 1.x devices used "PSphere-<model>-<id>".
        assertEquals("ab12cd34", Discovery.idOf("PSphere-Pixel 7-ab12cd34"))
        assertNull(Discovery.idOf("SomethingElse-ab12"))
        assertNull(Discovery.idOf("PSphere-"))
    }

    @Test
    fun settingsAreKeptAcrossRestarts() {
        val file = File(tempDir(), "desktop.properties")
        DesktopPrefs(file).apply {
            assertFalse(onboarded)
            assertTrue(keepInTray, "keeping running in the tray is on by default")
            onboarded = true
            keepInTray = false
        }
        DesktopPrefs(file).apply {
            assertTrue(onboarded)
            assertFalse(keepInTray)
        }
    }

    @Test
    fun onlyOneCopyRunsAndASecondOneCanAskItToShow() {
        val dir = tempDir()
        val first = SingleInstance(dir)
        assertTrue(first.acquired)
        var shown = 0
        val lock = Object()
        first.listen { synchronized(lock) { shown++; lock.notifyAll() } }
        val second = SingleInstance(dir)
        assertFalse(second.acquired)
        assertTrue(second.askRunningToShow())
        synchronized(lock) { if (shown == 0) lock.wait(3_000) }
        assertEquals(1, shown)
    }

    @Test
    fun aPhotoGetsASmallUprightPreview() {
        val dir = tempDir()
        val big = BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color(30, 120, 200); fillRect(0, 0, 3000, 2000); dispose() }
        }
        ImageIO.write(big, "jpg", File(dir, "photo.jpg"))
        val preview = WindowsPlatform().makePreview(FileSystemFolder(dir), "photo.jpg", Media.PHOTO)
        assertNotNull(preview)
        val img = ImageIO.read(ByteArrayInputStream(preview))
        assertEquals(WindowsPlatform.PREVIEW_PX, max(img.width, img.height))
        assertTrue(img.width > img.height, "landscape stays landscape")
        assertTrue(preview.size < 100_000, "a preview is small: ${preview.size} bytes")
    }

    @Test
    fun filesThatArentPhotosGetNoPreviewOrDate() {
        val dir = tempDir()
        File(dir, "notes.txt").writeText("not a photo")
        val platform = WindowsPlatform()
        val folder = FileSystemFolder(dir)
        assertNull(platform.makePreview(folder, "notes.txt", Media.PHOTO))
        assertNull(platform.makePreview(folder, "missing.jpg", Media.PHOTO))
        assertNull(platform.takenAt(folder, FileInfo("notes.txt", 11, 0), Media.PHOTO))
    }

    @Test
    fun onlyExistingFoldersOpen() {
        val dir = tempDir()
        val platform = WindowsPlatform()
        assertNotNull(platform.openFolder(dir.path))
        assertNull(platform.openFolder(File(dir, "gone").path))
    }
}
