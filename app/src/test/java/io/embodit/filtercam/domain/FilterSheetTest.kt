package io.embodit.filtercam.domain

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Design verification.pixelChecks: 6 sample photos × 8 v1 filters through the same engine the app uses. Inputs are
 * 640 x 480 RGB files written by tools/filters/filter_sheet.py (build/sheet-in/<sample>.rgb); each cell goes to
 * build/sheet-out/<sample>_<filter>.rgb (w, h in the name) and the script assembles the PNG sheet.
 */
class FilterSheetTest {
    @Test
    fun writesTheCells() {
        val inDir = File("build/sheet-in")
        assumeTrue(inDir.isDirectory)
        val out = File("build/sheet-out").apply { mkdirs() }
        val luts = HashMap<String, Lut3D>()
        for (f in inDir.listFiles { x -> x.name.endsWith(".rgb") }!!) {
            val b = f.readBytes()
            val px = IntArray(640 * 480) { i -> (0xFF shl 24) or ((b[i * 3].toInt() and 0xFF) shl 16) or ((b[i * 3 + 1].toInt() and 0xFF) shl 8) or (b[i * 3 + 2].toInt() and 0xFF) }
            val src = Img(640, 480, px)
            for (d in Filters.usable) {
                val lut = d.params.lut?.let { k -> luts.getOrPut(k) { CubeParser.parse(File("src/main/assets/luts/$k.cube").readText()) } }
                val r = FilterEngine.render(src, Look(d, lut, 1f, 7, if (d.stampByDefault) "'26 11 10" else null))
                val bytes = ByteArray(r.w * r.h * 3)
                for (i in r.px.indices) {
                    bytes[i * 3] = (r.px[i] shr 16).toByte()
                    bytes[i * 3 + 1] = (r.px[i] shr 8).toByte()
                    bytes[i * 3 + 2] = r.px[i].toByte()
                }
                File(out, "${f.nameWithoutExtension}_${d.id}_${r.w}x${r.h}.rgb").writeBytes(bytes)
            }
        }
    }
}
