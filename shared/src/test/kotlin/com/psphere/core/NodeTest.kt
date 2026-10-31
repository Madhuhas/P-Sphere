package com.psphere.core

import com.psphere.core.model.Include
import com.psphere.core.model.ItemState
import com.psphere.core.net.OfferEntry
import com.psphere.core.node.CodeConfirmer
import com.psphere.core.node.PairingOffer
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
import java.io.File

class NodeTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var phone: TestDevice
    private lateinit var tablet: TestDevice
    private lateinit var laptop: TestDevice

    @Before fun setUp() {
        phone = TestDevice("Phone", tmp.root)
        tablet = TestDevice("Tablet", tmp.root)
        laptop = TestDevice("Laptop", tmp.root)
    }

    @After fun tearDown() { listOf(phone, tablet, laptop).forEach { it.close() } }

    private fun linkAll() {
        phone.linkTo(tablet); phone.linkTo(laptop); tablet.linkTo(laptop)
    }

    private fun File.text(rel: String) = File(this, rel).readText()

    // ── Linking ──────────────────────────────────────────────────────────────

    @Test fun linkingWithQrCodeLinksBothSides() {
        phone.linkTo(tablet)
        assertEquals("Tablet", phone.node.device(tablet.id)?.name)
        assertEquals("Phone", tablet.node.device(phone.id)?.name)
    }

    @Test fun qrCodeIsSingleUse() {
        val offer = tablet.node.openPairing(listOf("127.0.0.1"), tablet.server.localPort)
        phone.node.linkWithQr(offer.toUri())
        try { laptop.node.linkWithQr(offer.toUri()); fail("Second use of the same code should fail") } catch (e: Exception) { }
        assertNull(tablet.node.device(laptop.id))
    }

    @Test fun wrongTokenIsRejected() {
        val offer = tablet.node.openPairing(listOf("127.0.0.1"), tablet.server.localPort)
        val forged = offer.copy(token = PairingOffer.newToken())
        try { phone.node.linkWithQr(forged.toUri()); fail() } catch (e: Exception) { }
        assertNull(tablet.node.device(phone.id))
        assertNull(phone.node.device(tablet.id))
    }

    @Test fun qrCodeForAnotherDeviceIsRejected() {
        // The QR says "Laptop" but the address answers with the tablet's key.
        val laptopOffer = laptop.node.openPairing(listOf("127.0.0.1"), laptop.server.localPort)
        val redirected = laptopOffer.copy(port = tablet.server.localPort)
        try { phone.node.linkWithQr(redirected.toUri()); fail() } catch (e: Exception) {
            assertTrue(e.message!!, e.message!!.contains("isn't the one in the QR code"))
        }
    }

    @Test fun linkingByCodeNeedsBothSidesToConfirm() {
        var shownOnTablet = ""
        var shownOnPhone = ""
        tablet.node.codeConfirmer = CodeConfirmer { _, code -> shownOnTablet = code; true }
        tablet.node.openPairing(listOf("127.0.0.1"), tablet.server.localPort)
        phone.node.linkWithCode(tablet.address, CodeConfirmer { _, code -> shownOnPhone = code; true })
        assertEquals(6, shownOnPhone.length)
        assertEquals(shownOnPhone, shownOnTablet)
        assertNotNull(tablet.node.device(phone.id))

        laptop.node.codeConfirmer = CodeConfirmer { _, _ -> false }
        laptop.node.openPairing(listOf("127.0.0.1"), laptop.server.localPort)
        try { phone.node.linkWithCode(laptop.address, CodeConfirmer { _, _ -> true }); fail() } catch (e: Exception) { }
        assertNull(laptop.node.device(phone.id))
        assertNull(phone.node.device(laptop.id))
    }

    @Test fun unlinkedDeviceCannotSync() {
        phone.linkTo(tablet)
        tablet.receiveInto()
        phone.backUp("Camera", phone.folder("DCIM/Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan()
        tablet.node.unlink(phone.id)
        val r = phone.syncWith(tablet)
        assertNotNull(r.error)
        assertFalse(File(tablet.dir, "disk/Backups/Phone").exists())
    }

    // ── Backing up ───────────────────────────────────────────────────────────

    @Test fun foldersGoOnlyToTheirDestinationsInBothDirections() {
        linkAll()
        val tabletBackups = tablet.receiveInto()
        val phoneBackups = phone.receiveInto()
        laptop.receiveInto()
        val camera = phone.folder("DCIM/Camera").apply { write("IMG_1.jpg", "one"); write("2026/IMG_2.jpg", "two") }
        val whatsapp = phone.folder("WhatsApp").apply { write("w.jpg", "w") }
        val docs = tablet.folder("Documents").apply { write("report.pdf", "pdf") }
        phone.backUp("Camera", camera, listOf(tablet, laptop))
        phone.backUp("WhatsApp", whatsapp, listOf(laptop))
        tablet.backUp("Documents", docs, listOf(phone), Include.ALL)
        phone.node.scan(); tablet.node.scan()

        val r = phone.syncWith(tablet)
        assertNull(r.error)
        assertEquals(2, r.sent)
        assertEquals(1, r.received)
        assertEquals("one", tabletBackups.text("Phone/Camera/IMG_1.jpg"))
        assertEquals("two", tabletBackups.text("Phone/Camera/2026/IMG_2.jpg"))
        assertFalse("WhatsApp is for the laptop only", File(tabletBackups, "Phone/WhatsApp").exists())
        assertEquals("pdf", phoneBackups.text("Tablet/Documents/report.pdf"))
    }

    @Test fun laptopCatchesUpFromTabletWhilePhoneIsOff() {
        linkAll()
        tablet.receiveInto()
        val laptopBackups = laptop.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "A"); write("b.jpg", "B") }
        val whatsapp = phone.folder("WhatsApp").apply { write("w.jpg", "W") }
        phone.backUp("Camera", camera, listOf(tablet, laptop))
        phone.backUp("WhatsApp", whatsapp, listOf(laptop))
        phone.node.scan()
        assertNull(phone.syncWith(tablet).error)
        phone.close() // the phone leaves

        val r = laptop.syncWith(tablet)
        assertNull(r.error)
        assertEquals("relayed from the tablet", 2, r.received)
        assertEquals("A", laptopBackups.text("Phone/Camera/a.jpg"))
        assertFalse("the tablet never had WhatsApp", File(laptopBackups, "Phone/WhatsApp").exists())
    }

    @Test fun bothDevicesStartingASyncAtOnceStillWorks() {
        phone.linkTo(tablet)
        val tabletBackups = tablet.receiveInto()
        val phoneBackups = phone.receiveInto()
        phone.backUp("Camera", phone.folder("Camera").apply { repeat(20) { write("p$it.jpg", "phone $it") } }, listOf(tablet))
        tablet.backUp("Camera", tablet.folder("Camera").apply { repeat(20) { write("t$it.jpg", "tablet $it") } }, listOf(phone))
        phone.node.scan(); tablet.node.scan()

        val results = java.util.concurrent.ConcurrentLinkedQueue<com.psphere.core.node.SyncResult>()
        val threads = listOf(
            kotlin.concurrent.thread { results += phone.syncWith(tablet) },
            kotlin.concurrent.thread { results += tablet.syncWith(phone) },
        )
        threads.forEach { it.join(30_000) }
        results.forEach { assertNull(it.error) }
        repeat(20) {
            assertEquals("phone $it", tabletBackups.text("Phone/Camera/p$it.jpg"))
            assertEquals("tablet $it", phoneBackups.text("Tablet/Camera/t$it.jpg"))
        }
        assertEquals("each file arrives once", 40, results.sumOf { it.sent })
    }

    @Test fun nothingIsSentTwice() {
        linkAll()
        tablet.receiveInto(); laptop.receiveInto()
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet, laptop))
        phone.node.scan()
        phone.syncWith(tablet)
        laptop.syncWith(tablet)
        val again = phone.syncWith(laptop)
        assertNull(again.error)
        assertEquals(0, again.sent)
        assertEquals(0, phone.syncWith(tablet).sent)
    }

    @Test fun deletingOnThePhoneKeepsTheBackup() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "A"); write("b.jpg", "B") }
        phone.backUp("Camera", camera, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)

        File(camera, "a.jpg").delete()
        assertEquals(1, phone.node.scan().deleted)
        phone.syncWith(tablet)
        assertEquals("A", backups.text("Phone/Camera/a.jpg"))
        assertEquals(1, tablet.node.library().size)
        assertEquals(2, tablet.node.library(includeDeleted = true).size)
    }

    @Test fun anEditedFileReplacesTheBackupAndKeepsTheOldVersion() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "version 1") }
        phone.backUp("Camera", camera, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)

        File(camera, "a.jpg").apply { writeText("version 2"); setLastModified(lastModified() + 5_000) }
        assertEquals(1, phone.node.scan().changed)
        assertEquals(1, phone.syncWith(tablet).sent)
        assertEquals("version 2", backups.text("Phone/Camera/a.jpg"))
        val old = File(backups, ".psphere/old").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, old.size)
        assertEquals("version 1", old[0].readText())
    }

    @Test fun unchangedFilesAreNotReadAgain() {
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, emptyList())
        assertEquals(1, phone.node.scan().changed)
        assertEquals(0, phone.node.scan().changed)
    }

    @Test fun newFilesAreSentWhenTheOtherDeviceStartsTheSync() {
        phone.linkTo(laptop)
        val backups = laptop.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "A") }
        phone.backUp("Camera", camera, listOf(laptop))
        phone.node.scan()

        // The phone hasn't scanned since b.jpg appeared; the laptop starts the sync.
        camera.write("b.jpg", "B")
        val r = laptop.syncWith(phone)
        assertNull(r.error)
        assertEquals(2, r.received)
        assertEquals("B", backups.text("Phone/Camera/b.jpg"))
    }

    @Test fun aScanThatRunsOutOfTimeMarksNothingDeleted() {
        val camera = phone.folder("Camera").apply { write("a.jpg", "A") }
        phone.backUp("Camera", camera, emptyList())
        phone.node.scan()
        camera.write("b.jpg", "B")

        val partial = phone.node.scan(budgetMs = 0)
        assertEquals(0, partial.changed)
        assertEquals(0, partial.deleted)
        assertEquals(1, phone.node.catalog.items(phone.id).count { it.state == ItemState.PRESENT })
        assertEquals(1, phone.node.scan().changed)
    }

    // ── Problems ─────────────────────────────────────────────────────────────

    @Test fun anInterruptedFileResumesWhereItStopped() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val big = "x".repeat(300_000) + "end"
        phone.backUp("Camera", phone.folder("Camera").apply { write("video.mp4", big) }, listOf(tablet))
        phone.node.scan()
        // Leave the first half on the tablet, as if the connection dropped.
        val hash = phone.node.catalog.items().single().hash
        backups.write(".psphere/tmp/$hash.part", big.substring(0, 150_000))

        assertEquals(1, phone.syncWith(tablet).sent)
        assertEquals(big, backups.text("Phone/Camera/video.mp4"))
        assertFalse(File(backups, ".psphere/tmp/$hash.part").exists())
    }

    @Test fun aCorruptedPartialFileIsDiscardedAndRetried() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val content = "y".repeat(100_000)
        phone.backUp("Camera", phone.folder("Camera").apply { write("v.mp4", content) }, listOf(tablet))
        phone.node.scan()
        val hash = phone.node.catalog.items().single().hash
        backups.write(".psphere/tmp/$hash.part", "garbage")

        val first = phone.syncWith(tablet)
        assertEquals(1, first.failed)
        assertFalse(File(backups, "Phone/Camera/v.mp4").exists())
        assertEquals(1, phone.syncWith(tablet).sent)
        assertEquals(content, backups.text("Phone/Camera/v.mp4"))
    }

    @Test fun aFullDriveIsReportedAndNothingIsWritten() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        tablet.node.setReserveBytes(Long.MAX_VALUE / 2)
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan()
        val r = phone.syncWith(tablet)
        assertNull(r.error)
        assertTrue(r.notes.toString(), r.notes.any { "running out of space" in it })
        assertFalse(File(backups, "Phone").exists())
    }

    @Test fun aDeviceWithoutABackupLocationSaysSo() {
        phone.linkTo(tablet)
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan()
        val r = phone.syncWith(tablet)
        assertTrue(r.notes.toString(), r.notes.any { "no backup location" in it })
    }

    @Test fun filesNotMeantForThisDeviceAreRefused() {
        linkAll()
        tablet.receiveInto()
        val rule = phone.backUp("WhatsApp", phone.folder("WhatsApp").apply { write("w.jpg", "W") }, listOf(laptop))
        phone.node.scan()
        phone.syncWith(tablet) // the tablet learns the rules
        val item = phone.node.catalog.items().single()
        val offer = listOf(OfferEntry(item.hash, item.size, phone.id, rule.ruleId, item.relPath))
        val plan = tablet.node.planIncoming(tablet.node.device(phone.id)!!, offer)
        assertTrue(plan.wanted.isEmpty())
    }

    @Test fun pathsFromOtherDevicesCannotEscapeTheBackupLocation() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val rule = phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)
        val item = phone.node.catalog.items().single()
        val offer = listOf(OfferEntry(item.hash, item.size, phone.id, rule.ruleId, "../../../evil.jpg"))
        val plan = tablet.node.planIncoming(tablet.node.device(phone.id)!!, offer)
        // Same content is already kept, so it is copied locally under a safe name.
        assertTrue(plan.wanted.isEmpty())
        assertTrue(File(backups, "Phone/Camera/evil.jpg").isFile)
        assertFalse(File(tmp.root, "evil.jpg").exists())
    }

    // ── Library, previews, Free up space ─────────────────────────────────────

    @Test fun everyDeviceSeesTheWholeLibraryWithPreviews() {
        linkAll()
        tablet.receiveInto(); laptop.receiveInto()
        phone.backUp("Camera", phone.folder("Camera").apply { write("p.jpg", "P") }, listOf(tablet))
        tablet.backUp("Camera", tablet.folder("Camera").apply { write("t.jpg", "T") }, listOf(laptop))
        phone.node.scan(); tablet.node.scan()
        phone.syncWith(tablet)

        val onPhone = phone.node.library()
        assertEquals(setOf("Phone", "Tablet"), onPhone.map { it.originName }.toSet())
        onPhone.forEach { assertTrue("preview for ${it.item.relPath}", phone.node.previews.has(it.item.hash)) }
        assertFalse("the tablet's photo isn't stored on the phone", onPhone.single { it.originName == "Tablet" }.onThisDevice)
    }

    @Test fun freeUpSpaceDeletesOnlyWhatIsSafeAndPhotosCanComeBack() {
        linkAll()
        tablet.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "A"); write("b.jpg", "B") }
        val whatsapp = phone.folder("WhatsApp").apply { write("w.jpg", "W") }
        phone.backUp("Camera", camera, listOf(tablet))
        phone.backUp("WhatsApp", whatsapp, listOf(laptop)) // laptop never synced
        phone.node.scan()
        phone.syncWith(tablet)

        val plan = phone.node.planFreeUp()
        assertEquals(setOf("a.jpg", "b.jpg"), plan.items.map { it.relPath }.toSet())

        val result = phone.node.freeUp(plan, online(tablet))
        assertEquals(2, result.freed)
        assertFalse(File(camera, "a.jpg").exists())
        assertTrue(File(whatsapp, "w.jpg").exists())

        // Still in the library, from previews, and downloadable again.
        val a = phone.node.library().single { it.item.relPath == "a.jpg" }
        assertEquals(ItemState.FREED, a.item.state)
        assertFalse(a.onThisDevice)
        assertTrue(phone.node.previews.has(a.item.hash))
        assertTrue(phone.node.download(a.item, online(tablet)))
        assertEquals("A", File(camera, "a.jpg").readText())
        assertTrue(phone.node.library().single { it.item.relPath == "a.jpg" }.onThisDevice)
    }

    @Test fun freeUpSpaceRechecksAndSkipsFilesNoLongerOnTheOtherDevice() {
        phone.linkTo(tablet)
        val backups = tablet.receiveInto()
        val camera = phone.folder("Camera").apply { write("a.jpg", "A"); write("b.jpg", "B") }
        phone.backUp("Camera", camera, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)

        File(backups, "Phone/Camera/a.jpg").delete() // someone cleaned up the tablet
        val result = phone.node.freeUp(phone.node.planFreeUp(), online(tablet))
        assertEquals(1, result.freed)
        assertTrue("a.jpg must stay on the phone", File(camera, "a.jpg").exists())
        assertFalse(File(camera, "b.jpg").exists())
    }

    @Test fun recentFilesCanBeKept() {
        phone.linkTo(tablet)
        tablet.receiveInto()
        val rule = phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)
        phone.node.updateRule(rule.ruleId, keepRecentDays = 30)
        assertTrue(phone.node.planFreeUp().items.isEmpty())
    }

    @Test fun fullSizePhotosLoadFromAnotherDevice() {
        phone.linkTo(tablet)
        tablet.receiveInto()
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "full size A") }, listOf(tablet))
        phone.node.scan(); phone.syncWith(tablet)
        val hash = tablet.node.library().single().item.hash
        phone.close()
        val laptop2 = laptop
        laptop2.linkTo(tablet)
        laptop2.syncWith(tablet)
        val file = laptop2.node.fetchToCache(hash, online(tablet))
        assertEquals("full size A", file?.readText())
    }

    @Test fun stateSurvivesARestart() {
        phone.linkTo(tablet)
        phone.backUp("Camera", phone.folder("Camera").apply { write("a.jpg", "A") }, listOf(tablet))
        phone.node.scan()
        phone.close()
        val again = TestDevice("Phone", tmp.root)
        assertEquals(phone.id, again.id)
        assertNotNull(again.node.device(tablet.id))
        assertEquals(1, again.node.ownRules().size)
        assertEquals(1, again.node.catalog.items().size)
        again.close()
    }
}
