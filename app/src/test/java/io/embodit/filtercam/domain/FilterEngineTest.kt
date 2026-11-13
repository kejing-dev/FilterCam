package io.embodit.filtercam.domain

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    private fun lut(name: String): Lut3D = CubeParser.parse(File("src/main/assets/luts/$name.cube").readText())

    /** A test picture with a sky gradient, a mid-grey band, saturated patches and dark shadows (all filters show here). */
    private fun picture(w: Int = 160, h: Int = 120): Img {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val r: Int
            val g: Int
            val b: Int
            when {
                y < h / 3 -> { r = 120 + x / 2; g = 170; b = 230 }
                y < h / 2 -> { val v = 30 + x * 200 / w; r = v; g = v; b = v }
                x < w / 3 -> { r = 220; g = 60; b = 50 }
                x < 2 * w / 3 -> { r = 60; g = 180; b = 80 }
                else -> { r = 20; g = 20; b = 30 }
            }
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Img(w, h, px)
    }

    private fun look(def: FilterDef, strength: Float = 1f, seed: Int = 7, compare: Boolean = false, stamp: String? = null) =
        Look(def, def.params.lut?.let { lut(it) }, strength, seed, stamp, compare)

    private fun meanDiff(a: Img, b: Img): Double {
        val x = if (a.w == b.w && a.h == b.h) a else a.resized(b.w, b.h)
        var sum = 0.0
        for (i in x.px.indices) for (sh in intArrayOf(16, 8, 0)) sum += abs(((x.px[i] shr sh) and 0xFF) - ((b.px[i] shr sh) and 0xFF))
        return sum / (x.px.size * 3)
    }

    @Test
    fun compareIsTheSourceUntouched() {
        val src = picture()
        for (d in Filters.usable) assertTrue(FilterEngine.render(src, look(d, compare = true)) === src)
    }

    @Test
    fun strengthZeroKeepsTheColoursOfTheSource() {
        val src = picture()
        for (d in Filters.usable.filter { !it.square }) {
            val out = FilterEngine.render(src, look(d, strength = 0f))
            assertTrue("${d.id} at strength 0", meanDiff(out, src) < 0.6)
        }
    }

    @Test
    fun everyV1FilterChangesThePicture() {
        // design acceptance.qaBlockers: 「任一 v1 滤镜在 48 格对照表里看不出差别」
        val src = picture()
        for (d in Filters.usable) {
            val out = FilterEngine.render(src, look(d))
            assertTrue("${d.id} differs from the source", meanDiff(out, src) > 6.0)
        }
    }

    @Test
    fun theV1FiltersDifferFromEachOther() {
        val src = picture()
        val outs = Filters.usable.associate { it.id to FilterEngine.render(src, look(it)) }
        val ids = outs.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val d = meanDiff(outs.getValue(ids[i]), outs.getValue(ids[j]))
            assertTrue("${ids[i]} vs ${ids[j]}: $d", d > 3.0)
        }
    }

    @Test
    fun blackAndWhiteHasNoColour() {
        val out = FilterEngine.render(picture(), look(Filters.F05))
        for (c in out.px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue(abs(r - g) <= 1 && abs(g - b) <= 1)
        }
    }

    @Test
    fun instantIsSquareWithAWhiteFrame() {
        val out = FilterEngine.render(picture(), look(Filters.F08))
        assertEquals(out.w, out.h)
        assertEquals(120, out.w)
        assertEquals(FilterEngine.FRAME_ARGB, out.px[2])
        assertEquals(FilterEngine.FRAME_ARGB, out.px[(out.h - 3) * out.w + out.w / 2])
    }

    @Test
    fun grainChangesWithTheSeedAndIsStableForOneSeed() {
        // design M5: each shot gets its own grain, the live picture keeps one
        val src = picture()
        val a = FilterEngine.render(src, look(Filters.F01, seed = 1))
        val a2 = FilterEngine.render(src, look(Filters.F01, seed = 1))
        val b = FilterEngine.render(src, look(Filters.F01, seed = 2))
        assertTrue(a.px.contentEquals(a2.px))
        assertFalse(a.px.contentEquals(b.px))
    }

    @Test
    fun theLeakMovesWithTheSeed() {
        val src = picture()
        val outs = (0 until 4).map { FilterEngine.render(src, look(Filters.F04, seed = it)) }
        for (i in 0 until 3) assertTrue(meanDiff(outs[i], outs[i + 1]) > 2.0)
    }

    @Test
    fun theDateStampIsOrangeInTheBottomRight() {
        val src = Img(320, 240, IntArray(320 * 240) { 0xFF202020.toInt() })
        val out = FilterEngine.stamp(src, "'26 11 4", StampStyle.SEVEN_SEG, 0.4f)
        var orange = 0
        var orangeLeft = 0
        for (y in 0 until 240) for (x in 0 until 320) {
            val c = out.px[y * 320 + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 200 && g in 90..160 && b < 80) {
                orange++
                if (x < 160 || y < 120) orangeLeft++
            }
        }
        assertTrue("stamp pixels $orange", orange > 100)
        assertEquals(0, orangeLeft)
    }

    @Test
    fun resizeAndCrop() {
        val src = picture(200, 100)
        val c = src.cropAspect(4, 3)
        assertEquals(133, c.w)
        assertEquals(100, c.h)
        val f = picture(300, 600).fitFourThree(1024)
        assertEquals(1024, f.w)
        assertEquals(768, f.h)
    }
}
