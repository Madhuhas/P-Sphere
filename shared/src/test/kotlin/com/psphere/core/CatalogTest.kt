package com.psphere.core

import com.psphere.core.catalog.Catalog
import com.psphere.core.model.DeviceRec
import com.psphere.core.model.HoldRec
import com.psphere.core.model.ItemRec
import com.psphere.core.model.ItemState
import com.psphere.core.model.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(origin: String, path: String, hash: String) =
        ItemRec(origin, 0, "r1", path, hash, 1, 1, 1, Media.PHOTO, ItemState.PRESENT)

    @Test fun onlyNewerRecordsWin() {
        val a = Catalog("a")
        a.putOwn(DeviceRec("a", 0, "Phone"))
        a.putOwn(DeviceRec("a", 0, "My Phone"))
        val b = Catalog("b")
        a.delta(b.vector()).forEach { b.apply(it) }
        assertEquals("My Phone", b.deviceName("a"))
        // An old copy arriving late changes nothing.
        assertFalse(b.apply(DeviceRec("a", 1, "Phone")))
        assertEquals("My Phone", b.deviceName("a"))
    }

    @Test fun writingTheSameThingTwiceIsNotAChange() {
        val a = Catalog("a")
        assertTrue(a.putOwn(DeviceRec("a", 0, "Phone")))
        assertFalse(a.putOwn(DeviceRec("a", 0, "Phone")))
        assertEquals(1L, a.vector()["a"])
    }

    @Test fun anInterruptedTransferResumes() {
        val a = Catalog("a")
        repeat(10) { a.putOwn(item("a", "p$it.jpg", "h$it")) }
        val b = Catalog("b")
        val all = a.delta(b.vector())
        all.take(4).forEach { b.apply(it) }  // connection dropped here
        val rest = a.delta(b.vector())
        assertEquals(6, rest.size)
        rest.forEach { b.apply(it) }
        assertEquals(10, b.items().size)
        assertTrue(a.delta(b.vector()).isEmpty())
    }

    @Test fun recordsRelayThroughAThirdDevice() {
        val phone = Catalog("phone"); val tablet = Catalog("tablet"); val laptop = Catalog("laptop")
        phone.putOwn(item("phone", "a.jpg", "h1"))
        phone.delta(tablet.vector()).forEach { tablet.apply(it) }
        tablet.delta(laptop.vector()).forEach { laptop.apply(it) }
        assertEquals("h1", laptop.item("phone", "r1", "a.jpg")?.hash)
    }

    @Test fun holdersFollowTheLatestRecord() {
        val a = Catalog("a")
        a.putOwn(HoldRec("a", 0, "h1", true))
        assertEquals(setOf("a"), a.holders("h1"))
        a.putOwn(HoldRec("a", 0, "h1", false))
        assertTrue(a.holders("h1").isEmpty())
    }

    @Test fun unlinkingForgetsADevicesRecords() {
        val a = Catalog("a")
        a.putOwn(item("a", "mine.jpg", "hm"))
        a.apply(DeviceRec("b", 1, "Tablet"))
        a.apply(item("b", "theirs.jpg", "ht"))
        a.apply(HoldRec("b", 2, "ht", true))
        assertEquals(2, a.items().size)
        assertEquals(setOf("b"), a.holders("ht"))

        val hashes = a.removeOrigin("b")

        assertEquals(setOf("ht"), hashes)
        assertEquals(null, a.deviceName("b"))
        assertEquals(1, a.items().size)                       // only this device's own item is left
        assertEquals("mine.jpg", a.items().first().relPath)
        assertTrue(a.holders("ht").isEmpty())
        assertTrue(a.itemsWithHash("ht").isEmpty())
        assertFalse("b" in a.vector())                        // no longer counts as a known origin
        // This device's own records are never dropped.
        assertTrue(a.removeOrigin("a").isEmpty())
        assertEquals(1, a.items().size)
    }

    @Test fun savesAndLoads() {
        val a = Catalog("a")
        a.putOwn(item("a", "x.jpg", "h"))
        a.apply(DeviceRec("b", 7, "Tablet"))
        val f = tmp.root.resolve("c.bin")
        a.save(f)
        val loaded = Catalog.load(f, "a")
        assertEquals("Tablet", loaded.deviceName("b"))
        assertEquals(a.vector(), loaded.vector())
        assertEquals(1, loaded.items().size)
    }
}
