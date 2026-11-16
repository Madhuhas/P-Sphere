package com.psphere.desktop

import com.psphere.desktop.smart.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {
    @Test fun positionsBecomeTownNames() {
        assertEquals("Hyderabad", Places.at(17.3616, 78.4747)?.city)      // Charminar
        assertEquals("Hyderabad", Places.at(17.4399, 78.4983)?.city)      // Secunderabad side: the big city wins
        assertEquals("India", Places.at(17.3616, 78.4747)?.country)
        assertEquals("Paris", Places.at(48.8584, 2.2945)?.city)            // Eiffel Tower
        assertEquals("New York City", Places.at(40.7580, -73.9855)?.city)  // Times Square
        assertEquals("Sydney", Places.at(-33.8568, 151.2153)?.city)        // Opera House: southern and eastern
        assertEquals("Suva", Places.at(-18.1416, 178.4419)?.city)          // near the date line
        assertNull(Places.at(0.0, -30.0))                                  // middle of the Atlantic
        // Countryside with no big town near: the region instead.
        val alps = Places.at(46.6242, 8.0414)!!                            // Grindelwald
        assertEquals("Bern", alps.city)
        assertEquals("Switzerland", alps.country)
    }
}
