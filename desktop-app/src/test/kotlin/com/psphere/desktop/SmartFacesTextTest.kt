package com.psphere.desktop

import com.psphere.desktop.engine.Images
import com.psphere.desktop.smart.Faces
import com.psphere.desktop.smart.Ocr
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * Real-model checks for faces and text. Skipped unless given:
 * -Dsmart.models=<dir with face-*.onnx, ocr-*> -Dsmart.faces=<portraits dir> -Dsmart.text=<photos with text dir>
 */
class SmartFacesTextTest {
    private val models = System.getProperty("smart.models")?.let(::File)

    @Test fun facesOfTheSamePersonMatch() {
        val dir = System.getProperty("smart.faces")?.let(::File)
        assumeTrue(models?.isDirectory == true && dir?.isDirectory == true)
        Faces(models!!).use { faces ->
            val found = dir!!.listFiles { f -> f.extension == "jpg" }!!.sorted().associate { f ->
                val t = System.nanoTime()
                val img = Images.readUpright(f, 1280)!!
                val fs = faces.find(img)
                println("${f.name}: ${fs.size} face(s) in ${(System.nanoTime() - t) / 1_000_000} ms " +
                    fs.joinToString { "[%.2f,%.2f %.2fx%.2f s=%.2f]".format(it.x, it.y, it.w, it.h, it.score) })
                f.nameWithoutExtension to fs
            }
            val singles = found.filter { it.value.isNotEmpty() && !it.key.contains("family") && !it.key.contains("room") }
            println("similarity of the main face in each portrait:")
            for ((a, fa) in singles) {
                println(a.padEnd(10) + singles.map { (_, fb) -> "%6.2f".format(dot(fa[0].embedding, fb[0].embedding)) }.joinToString(""))
            }
            // Faces in the group photos: which known person does each look like?
            for (g in found.keys.filter { it.contains("family") || it.contains("room") }) {
                found.getValue(g).forEachIndexed { i, f ->
                    val best = singles.maxByOrNull { dot(f.embedding, it.value[0].embedding) }!!
                    println("$g face $i looks most like ${best.key} (%.2f)".format(dot(f.embedding, best.value[0].embedding)))
                }
            }
        }
    }

    @Test fun textInPhotosIsRead() {
        val dir = System.getProperty("smart.text")?.let(::File)
        assumeTrue(models?.isDirectory == true && dir?.isDirectory == true)
        Ocr(models!!).use { ocr ->
            val shot = BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    color = Color.WHITE; fillRect(0, 0, 900, 500); color = Color.BLACK
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 34)
                    drawString("Invoice #20431", 40, 80)
                    drawString("Electricity bill for October", 40, 150)
                    drawString("Total due: Rs 2,450.00", 40, 220)
                    font = Font(Font.SANS_SERIF, Font.BOLD, 28); drawString("Pay before 25 Nov 2026", 40, 300)
                    dispose()
                }
            }
            val t = System.nanoTime()
            println("screenshot (${(System.nanoTime() - t) / 1_000_000} ms):\n" + ocr.read(shot).prependIndent("  "))
            dir!!.listFiles { f -> f.extension == "jpg" }!!.sorted().forEach { f ->
                val img = Images.readUpright(f, 1600)!!
                val t2 = System.nanoTime()
                val text = ocr.read(img)
                println("${f.name} (${(System.nanoTime() - t2) / 1_000_000} ms):\n" + text.prependIndent("  "))
            }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
}
