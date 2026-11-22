package com.psphere.core

import com.psphere.core.node.DeviceStatus
import com.psphere.core.node.Node
import com.psphere.core.node.RingResult
import org.junit.After
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

class StatusTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var phone: TestDevice
    private lateinit var laptop: TestDevice

    @Before fun setUp() {
        phone = TestDevice("Phone", tmp.root)
        laptop = TestDevice("Laptop", tmp.root)
        phone.linkTo(laptop)
        phone.node.statusProvider = { DeviceStatus(64, false, 38_000_000_000, 128_000_000_000) }
        laptop.node.statusProvider = { DeviceStatus(-1, false, 212_000_000_000, 480_000_000_000) }
    }

    @After fun tearDown() { listOf(phone, laptop).forEach { it.close() } }

    @Test fun bothDevicesLearnEachOthersBatteryAndFreeSpace() {
        assertNull(phone.node.status(laptop.id))
        val before = System.currentTimeMillis()
        assertEquals(1, phone.node.statusSync(online(laptop)))

        val ofLaptop = phone.node.status(laptop.id)!!
        assertFalse("a desktop PC has no battery", ofLaptop.hasBattery)
        assertEquals(212_000_000_000, ofLaptop.freeBytes)
        assertTrue(ofLaptop.at >= before)

        // The asking device sent its own along, so the laptop knows the phone's too.
        val ofPhone = laptop.node.status(phone.id)!!
        assertEquals(64, ofPhone.battery)
        assertEquals(38_000_000_000, ofPhone.freeBytes)
        assertTrue(ofPhone.hasSpace)
    }

    @Test fun theLastStatusIsKeptAfterARestartAndForgottenOnUnlink() {
        phone.node.statusSync(online(laptop))
        phone.node.save()
        val again = Node(File(phone.dir, "data"), "Phone", object : com.psphere.core.node.Platform {
            override fun openFolder(location: String) = null
        })
        assertEquals(212_000_000_000, again.status(laptop.id)?.freeBytes)
        again.unlink(laptop.id)
        assertNull(again.status(laptop.id))
    }

    @Test fun devicesThatArentOnlineAreNotAsked() {
        assertEquals(0, phone.node.statusSync(emptyMap()))
        assertNull(phone.node.status(laptop.id))
    }

    @Test fun aDeviceCanRingAnotherAndStopIt() {
        val calls = Collections.synchronizedList(ArrayList<String>())
        laptop.node.onRing = { id, name, on -> calls += "$name:$on"; assertEquals(phone.id, id); true }
        assertEquals(RingResult.OK, phone.node.ring(laptop.id, true, online(laptop)))
        assertEquals(RingResult.OK, phone.node.ring(laptop.id, false, online(laptop)))
        assertEquals(listOf("Phone:true", "Phone:false"), calls)
    }

    @Test fun ringingCanBeTurnedOff() {
        laptop.node.onRing = { _, _, _ -> false }
        assertEquals(RingResult.TURNED_OFF, phone.node.ring(laptop.id, true, online(laptop)))
    }

    @Test fun aDeviceThatIsntAroundCantBeRung() {
        val gone = laptop.address
        laptop.close()
        assertEquals(RingResult.UNREACHABLE, phone.node.ring(laptop.id, true, mapOf(laptop.id to gone)))
    }

    @Test fun anUnlinkedDeviceCantRingOrAsk() {
        var rang = false
        laptop.node.onRing = { _, _, _ -> rang = true; true }
        laptop.node.unlink(phone.id)
        assertEquals(RingResult.UNREACHABLE, TestDevice("Stranger", tmp.root).use { it.node.ring(laptop.id, true, online(laptop)) })
        assertNotNull(phone.node.device(laptop.id))
        phone.node.ring(laptop.id, true, online(laptop))
        assertFalse("the laptop no longer accepts the phone", rang)
    }
}
