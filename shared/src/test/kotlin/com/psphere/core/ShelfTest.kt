package com.psphere.core

import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.core.shelf.ShelfStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class ShelfTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var phone: TestDevice
    private lateinit var tablet: TestDevice
    private lateinit var laptop: TestDevice

    @Before fun setUp() {
        phone = TestDevice("Phone", tmp.root)
        tablet = TestDevice("Tablet", tmp.root)
        laptop = TestDevice("Laptop", tmp.root)
        phone.linkTo(tablet); phone.linkTo(laptop); tablet.linkTo(laptop)
    }

    @After fun tearDown() { listOf(phone, tablet, laptop).forEach { it.close() } }

    private fun TestDevice.texts() = node.shelfItems().map { it.item.text }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test fun textAndLinksReachEveryLinkedDevice() {
        phone.node.shelfAddText("  Meeting at 5, room 2  ")
        Thread.sleep(5)
        phone.node.shelfAddText("https://example.com/menu")
        assertEquals(2, phone.node.shelfSync(online(tablet, laptop)))

        for (d in listOf(tablet, laptop)) {
            val items = d.node.shelfItems()
            assertEquals(listOf("https://example.com/menu", "Meeting at 5, room 2"), items.map { it.item.text })
            assertEquals(listOf(ShelfItem.Kind.LINK, ShelfItem.Kind.TEXT), items.map { it.item.kind })
            assertTrue(items.all { it.originName == "Phone" && it.here })
        }
    }

    @Test fun theOtherDeviceIsToldWhenSomethingArrives() {
        val arrived = Collections.synchronizedList(ArrayList<ShelfEntry>())
        laptop.node.onShelfAdded = { arrived += it }
        phone.node.shelfAddText("hello")
        phone.node.shelfSync(online(laptop))
        assertEquals(listOf("hello"), arrived.map { it.item.text })
        // Hearing about it again isn't news.
        phone.node.shelfSync(online(laptop))
        assertEquals(1, arrived.size)
    }

    @Test fun smallFilesCopyOverByThemselvesAndBigOnesWhenOpened() {
        val small = ByteArray(300_000) { (it % 251).toByte() }
        val big = ByteArray((ShelfStore.AUTO_FETCH_BYTES + 1).toInt()) { (it % 13).toByte() }
        phone.node.shelfAddFile("receipt.pdf", small.inputStream())
        val bigItem = phone.node.shelfAddFile("video.mp4", big.inputStream())
        phone.node.shelfSync(online(laptop))

        waitFor("the small file") { laptop.node.shelfItems().first { it.item.text == "receipt.pdf" }.here }
        val smallOnLaptop = laptop.node.shelfItems().first { it.item.text == "receipt.pdf" }
        assertArrayEquals(small, laptop.node.shelfFile(smallOnLaptop.item)!!.readBytes())

        Thread.sleep(300)
        assertFalse("big files wait until opened", laptop.node.shelfItems().first { it.item.text == "video.mp4" }.here)
        val fetched = laptop.node.shelfFile(bigItem, online(phone))
        assertNotNull(fetched)
        assertEquals(big.size.toLong(), fetched!!.length())
        assertTrue(laptop.node.shelfItems().first { it.item.text == "video.mp4" }.here)
    }

    @Test fun aFileCanComeFromADeviceOtherThanTheOneThatAddedIt() {
        val bytes = "notes".repeat(1000).toByteArray()
        val item = phone.node.shelfAddFile("notes.txt", bytes.inputStream())
        phone.node.shelfSync(online(tablet))
        waitFor("the tablet's copy") { tablet.node.shelf.hasFile(item.hash) }
        phone.close() // the phone is gone; the laptop gets the item and file through the tablet
        tablet.node.shelfSync(online(laptop))
        val got = laptop.node.shelfFile(laptop.node.shelfItems().single().item, online(tablet))
        assertArrayEquals(bytes, got!!.readBytes())
    }

    @Test fun removingTakesItOffEveryDeviceEvenOnesThatWereAway() {
        val item = phone.node.shelfAddText("old address")
        phone.node.shelfSync(online(tablet, laptop))
        assertEquals(listOf("old address"), laptop.texts())

        laptop.close() // the laptop is away when the phone removes it
        phone.node.shelfRemove(listOf(item!!.id))
        phone.node.shelfSync(online(tablet))
        assertEquals(emptyList<String>(), tablet.texts())

        // The laptop comes back and hears about it from the tablet; the text itself isn't passed on.
        val back = TestDevice("Laptop", tmp.root)
        try {
            back.node.shelfSync(online(tablet))
            assertEquals(emptyList<String>(), back.texts())
            assertEquals("", back.node.shelf.get(item.id)!!.text)
        } finally { back.close() }
    }

    @Test fun aRemovalWinsOverAPinMadeAtTheSameTime() {
        val item = phone.node.shelfAddText("keep?")!!
        phone.node.shelfSync(online(laptop))
        laptop.node.shelfPin(item.id, true)
        phone.node.shelfRemove(listOf(item.id))
        laptop.node.shelfSync(online(phone))
        assertEquals(emptyList<String>(), phone.texts())
        assertEquals(emptyList<String>(), laptop.texts())
    }

    @Test fun pinnedItemsComeFirstOnEveryDevice() {
        val first = phone.node.shelfAddText("first")!!
        Thread.sleep(5)
        phone.node.shelfAddText("second")
        phone.node.shelfSync(online(laptop))
        assertEquals(listOf("second", "first"), laptop.texts())
        laptop.node.shelfPin(first.id, true)
        laptop.node.shelfSync(online(phone))
        assertEquals(listOf("first", "second"), phone.texts())
        assertTrue(phone.node.shelfItems().first().item.pinned)
    }

    @Test fun aDeviceThatWasAwayCatchesUpDuringANormalBackupSync() {
        phone.node.shelfAddText("from the phone")
        tablet.node.shelfAddText("from the tablet")
        tablet.syncWith(phone)
        assertEquals(setOf("from the phone", "from the tablet"), phone.texts().toSet())
        assertEquals(setOf("from the phone", "from the tablet"), tablet.texts().toSet())
    }

    @Test fun anUnlinkedDevicesItemsGoAndDontComeBackThroughAnother() {
        laptop.node.shelfAddText("from the laptop")
        laptop.node.shelfSync(online(phone, tablet))
        assertEquals(listOf("from the laptop"), phone.texts())

        phone.node.unlink(laptop.id)
        assertEquals(emptyList<String>(), phone.texts())
        tablet.node.shelfSync(online(phone))
        assertEquals(emptyList<String>(), phone.texts())
        assertEquals(listOf("from the laptop"), tablet.texts())
    }

    @Test fun veryLongTextGoesOnAsATextFile() {
        val long = "x".repeat(ShelfStore.MAX_TEXT_BYTES + 1)
        val item = phone.node.shelfAddText(long)!!
        assertEquals(ShelfItem.Kind.FILE, item.kind)
        assertTrue(item.text.endsWith(".txt"))
        assertEquals(long, phone.node.shelfFile(item)!!.readText())
        assertNull(phone.node.shelfAddText("   "))
    }

    @Test fun oldItemsArePointedOutButNeverClearedOnTheirOwn() {
        var now = 1_000_000_000_000L
        val dir = File(tmp.root, "store")
        val store = ShelfStore(dir) { now }
        val a = store.addText("me", "a")
        val pinned = store.addText("me", "b")
        store.setPinned(pinned.id, true)
        assertFalse(store.shouldAskAboutOld())

        now += ShelfStore.OLD_MS + 1
        store.addText("me", "new")
        assertEquals(listOf(a.id), store.old().map { it.id })
        assertTrue(store.shouldAskAboutOld())

        store.keepOld()
        assertFalse("not asked again right after keeping", store.shouldAskAboutOld())
        now += ShelfStore.OLD_MS + 1
        assertTrue("asked again a week later", store.shouldAskAboutOld())
        // Still there after all that time, and after a restart.
        assertEquals(3, ShelfStore(dir) { now }.live().size)
    }

    @Test fun theSameFileTwiceIsStoredOnceAndRemovedWhenNothingNeedsIt() {
        val store = ShelfStore(File(tmp.root, "store2")) { System.currentTimeMillis() }
        val bytes = "same".toByteArray()
        val one = store.addFile("me", "a.txt", bytes.inputStream())
        val two = store.addFile("me", "b.txt", bytes.inputStream())
        assertEquals(one.hash, two.hash)
        store.remove(listOf(one.id))
        assertTrue(store.hasFile(one.hash))
        store.remove(listOf(two.id))
        assertFalse(store.hasFile(one.hash))
    }

    @Test fun namesFromOtherDevicesCantBeUsedAsPaths() {
        val item = phone.node.shelfAddFile("..\\..\\evil/../x:y.txt", "x".byteInputStream())
        assertFalse(item.text.contains('/') || item.text.contains('\\') || item.text.contains(':'))
    }
}
