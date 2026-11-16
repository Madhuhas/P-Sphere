package com.psphere.desktop

import com.psphere.desktop.smart.Facts
import com.psphere.desktop.smart.FoundFace
import com.psphere.desktop.smart.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt
import kotlin.random.Random

class FactsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A made-up face fingerprint: [person]'s direction plus a little noise, like real photos of one person. */
    private fun face(person: Int, seed: Int): FoundFace {
        val base = Random(person * 1000)
        val noise = Random(seed)
        val v = FloatArray(128) { base.nextFloat() - 0.5f + (noise.nextFloat() - 0.5f) * 0.25f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FoundFace(0.4f, 0.3f, 0.2f, 0.2f, 0.9f, FloatArray(128) { v[it] / n })
    }

    private val paris = Place("Paris|FR|Île-de-France", "Paris", "Île-de-France", "France")

    @Test fun facesAreGroupedIntoPeopleAndNamesStick() {
        val file = tmp.newFile("facts.bin").apply { delete() }
        val f = Facts(file)
        f.put("a1", listOf(face(1, 1)), listOf(byteArrayOf(1)), "", null)
        f.put("a2", listOf(face(1, 2)), listOf(null), "Invoice 42", paris)
        f.put("b1", listOf(face(2, 3), face(1, 4)), listOf(null, null), "", paris)
        f.put("b2", listOf(face(2, 5)), listOf(null), "", null)
        f.put("c1", listOf(face(3, 6)), listOf(null), "", null) // in one photo only: not shown yet

        val people = f.people()
        assertEquals("two people in two or more photos", 2, people.size)
        val first = people.first { "a1" in it.second }
        assertEquals(setOf("a1", "a2", "b1"), first.second)
        assertEquals(setOf("b1", "b2"), people.first { "b2" in it.second }.second)

        f.name(first.first.id, "Mom")
        assertEquals("named people come first", "Mom", f.people().first().first.name)
        assertEquals(setOf("a2"), f.withText(listOf("invoice")))
        assertEquals(setOf("a2", "b1"), f.photosAt(paris.id))

        // Saved and loaded again: everything is still there.
        f.save()
        val again = Facts(file)
        assertEquals("Mom", again.people().first().first.name)
        assertEquals(setOf("a1", "a2", "b1"), again.photosOf(again.people().first().first.id))
        assertTrue(again.isDone("c1"))
    }

    @Test fun givingTheSameNameJoinsTwoPeople() {
        val f = Facts(tmp.newFile("facts.bin").apply { delete() })
        f.put("a1", listOf(face(1, 1)), listOf(null), "", null)
        f.put("a2", listOf(face(1, 2)), listOf(null), "", null)
        f.put("b1", listOf(face(2, 3)), listOf(null), "", null)
        f.put("b2", listOf(face(2, 4)), listOf(null), "", null)
        val (p1, p2) = f.people().map { it.first.id }
        f.name(p1, "Dad")
        f.name(p2, "dad")
        val people = f.people()
        assertEquals(1, people.size)
        assertEquals(setOf("a1", "a2", "b1", "b2"), people.single().second)
    }

    @Test fun photosThatLeaveTheLibraryAreForgotten() {
        val f = Facts(tmp.newFile("facts.bin").apply { delete() })
        f.put("a1", listOf(face(1, 1)), listOf(null), "", paris)
        f.put("a2", listOf(face(1, 2)), listOf(null), "", paris)
        f.retain(setOf("a1"))
        assertTrue("one photo left, so the person no longer shows", f.people().isEmpty())
        assertEquals(setOf("a1"), f.photosAt(paris.id))
    }
}
