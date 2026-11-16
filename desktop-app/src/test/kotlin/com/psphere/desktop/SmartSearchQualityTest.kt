package com.psphere.desktop

import com.psphere.desktop.smart.Clip
import com.psphere.desktop.smart.SmartIndex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real search model on real photos. Skipped unless both folders are given:
 * gradlew test -Dsmart.models=<dir> -Dsmart.photos=<dir>
 */
class SmartSearchQualityTest {
    @Test fun searchFindsTheRightPhotos() {
        val models = System.getProperty("smart.models")?.let(::File)
        val photos = System.getProperty("smart.photos")?.let(::File)
        assumeTrue(models?.isDirectory == true && photos?.isDirectory == true)
        Clip(models!!, threads = 2).use { clip ->
            val index = SmartIndex(File.createTempFile("idx", ".bin").apply { delete() })
            val t0 = System.nanoTime()
            val files = photos!!.listFiles { f -> f.extension == "jpg" }!!.sorted()
            files.forEach { f -> index.put(f.nameWithoutExtension, clip.embedImage(f.readBytes())!!) }
            println("embedded ${files.size} photos in ${(System.nanoTime() - t0) / 1_000_000} ms")
            val queries = listOf(
                "dog" to "dog", "a cat" to "cat", "horses in a field" to "horses", "bear" to "bear",
                "pizza" to "pizza", "hot dog" to "hotdog", "yellow flower" to "flower", "sky" to "clouds",
                "bicycle" to "bike", "eiffel tower" to "eiffel", "food" to "pizza", "animal" to "dog",
                "tower in paris" to "eiffel", "something to eat" to "pizza",
            )
            var ok = 0
            for ((q, want) in queries) {
                val t = System.nanoTime()
                val hits = index.search(clip.embedText(q), 10, -1f)
                val ms = (System.nanoTime() - t) / 1_000_000
                val top = hits.take(4).joinToString { "%s %.3f".format(it.first, it.second) }
                val pass = hits.first().first == want || (q in setOf("food", "something to eat", "animal") && hits.first().first in setOf("pizza", "hotdog", "dog", "cat", "bear", "horses"))
                if (pass) ok++
                println("${if (pass) "OK  " else "MISS"} '$q' (${ms} ms): $top")
            }
            println("$ok/${queries.size}")
            check(ok >= queries.size - 1)
        }
    }
}
