package com.psphere.core

import com.psphere.core.update.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test fun newerVersionsAreDetected() {
        assertTrue(UpdateCheck.isNewer("v2.1", "2.0"))
        assertTrue(UpdateCheck.isNewer("2.1", "2.0.0"))
        assertTrue(UpdateCheck.isNewer("v3.0", "2.9.9"))
        assertTrue(UpdateCheck.isNewer("2.0.1", "2.0"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("v2.0", "2.0"))
        assertFalse(UpdateCheck.isNewer("2.0.0", "2.0"))   // 2.0.0 == 2.0
        assertFalse(UpdateCheck.isNewer("2.0", "2.0.0"))
        assertFalse(UpdateCheck.isNewer("v1.9", "2.0"))
        assertFalse(UpdateCheck.isNewer("v2.0-beta", "2.0")) // suffix ignored -> equal
    }

    @Test fun tagIsReadFromTheReleasesArray() {
        val json = """[{"tag_name":"v2.1","name":"PSphere 2.1"},{"tag_name":"v2.0"}]"""
        assertEquals("v2.1", UpdateCheck.latestTag(json))
    }

    @Test fun missingTagReturnsNull() {
        assertNull(UpdateCheck.latestTag("""[]"""))
        assertNull(UpdateCheck.latestTag("""{"message":"Not Found"}"""))
    }
}
