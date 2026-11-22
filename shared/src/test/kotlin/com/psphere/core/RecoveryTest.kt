package com.psphere.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A device whose files on disk got damaged or half-saved comes back without losing its links or backups. */
class RecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aDeviceThatLostItsCatalogGetsItsFoldersBackAndKeepsBackingUp() {
        val laptop = TestDevice("Laptop", tmp.root)
        var phone = TestDevice("Phone", tmp.root)
        try {
            phone.linkTo(laptop)
            val backups = laptop.receiveInto()
            val camera = phone.folder("Camera").apply { write("A.jpg", "first photo") }
            phone.backUp("Camera", camera, listOf(laptop))
            phone.node.scan()
            phone.syncWith(laptop)
            assertTrue(File(backups, "Phone/Camera/A.jpg").isFile)

            // The phone's catalog is lost (damaged file); its identity and settings are fine.
            phone.close()
            File(phone.dir, "data/catalog.bin").delete()
            phone = TestDevice("Phone", tmp.root)
            assertTrue("the folder is gone with the catalog", phone.node.ownRules().isEmpty())

            camera.write("B.jpg", "photo taken after")
            phone.syncWith(laptop)
            assertEquals("the laptop gave the folder back", listOf("Camera"), phone.node.ownRules().map { it.first.label })
            phone.node.scan()
            phone.syncWith(laptop)
            assertEquals("photo taken after", File(backups, "Phone/Camera/B.jpg").readText())

            // And later changes keep flowing.
            camera.write("C.jpg", "and another")
            phone.node.scan()
            phone.syncWith(laptop)
            assertEquals("and another", File(backups, "Phone/Camera/C.jpg").readText())
        } finally {
            phone.close(); laptop.close()
        }
    }

    @Test fun settingsSurviveASaveThatWasCutOffBeforeTheFinalStep() {
        val laptop = TestDevice("Laptop", tmp.root)
        var phone = TestDevice("Phone", tmp.root)
        try {
            phone.linkTo(laptop)
            phone.close()
            // As if the app died after writing the new copy but before putting it in place.
            val state = File(phone.dir, "data/state.bin")
            assertTrue(state.renameTo(File(state.path + ".tmp")))
            val catalog = File(phone.dir, "data/catalog.bin")
            assertTrue(catalog.renameTo(File(catalog.path + ".tmp")))

            phone = TestDevice("Phone", tmp.root)
            assertEquals("still linked", listOf("Laptop"), phone.node.devices().map { it.name })
        } finally {
            phone.close(); laptop.close()
        }
    }
}
