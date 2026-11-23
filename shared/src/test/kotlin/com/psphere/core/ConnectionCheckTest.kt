package com.psphere.core

import com.psphere.core.node.HostPort
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
import java.net.ServerSocket

class ConnectionCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var phone: TestDevice
    private lateinit var laptop: TestDevice

    @Before fun setUp() {
        phone = TestDevice("Phone", tmp.root)
        laptop = TestDevice("Laptop", tmp.root)
        phone.linkTo(laptop)
    }

    @After fun tearDown() { listOf(phone, laptop).forEach { it.close() } }

    @Test fun aDeviceThatAnswersIsReachable() {
        assertTrue(phone.node.canReach(laptop.id, online(laptop)))
        laptop.close()
        assertFalse(phone.node.canReach(laptop.id, online(laptop)))
    }

    @Test fun theOtherDeviceCanConnectBackWhenNothingBlocksIt() {
        val reached = laptop.node.askToConnectBack(phone.id, online(phone), listOf("127.0.0.1"), laptop.server.localPort)
        assertEquals(true, reached)
        // The phone connected to the laptop, so the laptop knows incoming connections work.
        assertNotNull(laptop.node.lastIncomingFrom(phone.id))
    }

    @Test fun connectingBackFailsWhenNothingListensThere() {
        // A port that's free: like a firewall dropping incoming connections.
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(false, laptop.node.askToConnectBack(phone.id, online(phone), listOf("127.0.0.1"), closedPort))
    }

    @Test fun connectingBackOnlyAcceptsTheAskingDevice() {
        // Something else answers at that address (a stranger's PSphere): not counted as reached.
        TestDevice("Stranger", tmp.root).use { stranger ->
            assertEquals(false, laptop.node.askToConnectBack(phone.id, online(phone), listOf("127.0.0.1"), stranger.server.localPort))
        }
    }

    @Test fun aDeviceThatIsntThereCantBeAsked() {
        val gone = phone.address
        phone.close()
        assertNull(laptop.node.askToConnectBack(phone.id, mapOf(phone.id to gone), listOf("127.0.0.1"), laptop.server.localPort))
    }

    @Test fun aSyncThatCantConnectSaysWhatToDo() {
        val gone = laptop.address
        laptop.close()
        val seen = phone.node.syncWith(laptop.id, listOf(gone))
        assertTrue(seen.error!!, "didn't answer" in seen.error!!)
        assertTrue("Check connection" in seen.error!!)
        val unseen = phone.node.syncWith(laptop.id, listOf(HostPort("127.0.0.1", 1)).drop(1))
        assertTrue(unseen.error!!, "PSphere needs to be open" in unseen.error!!)
    }
}
