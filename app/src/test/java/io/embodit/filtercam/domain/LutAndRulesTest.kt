package io.embodit.filtercam.domain

import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LutAndRulesTest {
    private fun cube(n: Int, f: (Float, Float, Float) -> Triple<Float, Float, Float> = { r, g, b -> Triple(r, g, b) }): String {
        val sb = StringBuilder("TITLE \"t\"\n# comment\nLUT_3D_SIZE $n\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val (x, y, z) = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            sb.append("$x $y $z\n")
        }
        return sb.toString()
    }

    @Test
    fun cubeGridsOf17_33_65AreReadAndResampledTo33() {
        for (n in listOf(17, 33, 65)) {
            val l = CubeParser.parse(cube(n))
            assertEquals(Lut3D.SIZE, l.size)
            val out = FloatArray(3)
            l.apply(0.3f, 0.6f, 0.9f, out)
            assertEquals(0.3f, out[0], 1e-3f)
            assertEquals(0.6f, out[1], 1e-3f)
            assertEquals(0.9f, out[2], 1e-3f)
        }
    }

    @Test
    fun redIsTheFastestIndex() {
        val l = CubeParser.parse(cube(17) { r, g, b -> Triple(r, 0f, 0f) })
        val out = FloatArray(3)
        l.apply(1f, 0f, 0f, out)
        assertEquals(1f, out[0], 1e-4f)
        l.apply(0f, 1f, 1f, out)
        assertEquals(0f, out[0], 1e-4f)
    }

    @Test
    fun otherGridsAndBrokenFilesAreRefused() {
        // design S11: 「这个 .cube 读不了（只认 17 / 33 / 65 格）」
        for (bad in listOf(cube(20), "LUT_1D_SIZE 1024\n0 0 0\n", cube(17).lines().dropLast(5).joinToString("\n"), "LUT_3D_SIZE 17\nfoo bar baz\n", "hello")) {
            try {
                CubeParser.parse(bad)
                fail("accepted: ${bad.take(30)}")
            } catch (e: CubeError) {
                // expected
            }
        }
    }

    @Test
    fun builtInLutsParse() {
        for (name in listOf("gold200", "ccd_cool", "trix", "cyber_split", "instant", "y2k_cool")) {
            val l = CubeParser.parse(File("src/main/assets/luts/$name.cube").readText())
            assertEquals(33, l.size)
        }
    }

    @Test
    fun noFisheyeAnywhere() {
        // gate B 2026-11-05: 「鱼眼 不用」
        assertTrue(Filters.builtIn.none { it.id == "F03" })
        val strings = File("src/main/res/values/strings.xml").readText() + File("src/main/res/values-zh/strings.xml").readText()
        assertFalse(strings.contains("鱼眼"))
        assertFalse(strings.contains("Fisheye", ignoreCase = true))
    }

    @Test
    fun eightUsableFiltersAndOneV11Card() {
        assertEquals(listOf("F01", "F02", "F04", "F05", "F06", "F07", "F08", "F09"), Filters.usable.map { it.id })
        assertEquals(listOf("F10"), Filters.builtIn.filter { !it.available }.map { it.id })
    }

    @Test
    fun roomCycleIsTheFiveRoomFilters() {
        assertEquals(listOf("F01", "F04", "F06", "F07", "F08"), Filters.roomCycle)
        assertEquals("F04", Filters.roomStep("F01", 1))
        assertEquals("F01", Filters.roomStep("F08", 1))
        assertEquals("F08", Filters.roomStep("F01", -1))
        assertEquals("F01", Filters.roomStep("F05", 1))
        for (id in Filters.roomCycle) assertNotNull(RoomLooks.of(id))
        for (id in listOf("F02", "F05", "F09")) assertNull(RoomLooks.of(id))
    }

    @Test
    fun stampOnByDefaultOnlyForTheDateFilters() {
        // design Q3: only F02 / F06 / F09 / F10 start with the stamp on
        assertEquals(setOf("F02", "F06", "F09", "F10"), Filters.builtIn.filter { it.stampByDefault }.map { it.id }.toSet())
    }

    @Test
    fun lutNamesAreCutAt14() {
        assertEquals("short", Filters.shortLutName("short"))
        assertEquals("Kodak Portra 4…", Filters.shortLutName("Kodak Portra 400 warm"))
        assertEquals(Filters.LUT_NAME_MAX + 1, Filters.shortLutName("x".repeat(40)).length)
    }

    @Test
    fun stampTexts() {
        assertEquals("'26 11 4", Stamp.text(StampFormat.SHORT, 2026, 11, 4))
        assertEquals("2026.11.04", Stamp.text(StampFormat.DOTTED, 2026, 11, 4))
        assertNull(Stamp.text(StampFormat.OFF, 2026, 11, 4))
    }

    // ---- P5 placement (profile D9 / D10 / D29)

    private fun head(pitchDeg: Float, yawDeg: Float = 0f, rollDeg: Float = 0f, at: Vec3 = Vec3(0.3f, 1.6f, -0.2f)): HeadPose {
        val yaw = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat())
        val pitch = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat())
        val roll = Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return HeadPose(at, (yaw * pitch * roll).normalized())
    }

    @Test
    fun roomPanelIsUprightBelowAndLeftWhateverTheHeadDoes() {
        for ((pitch, roll) in listOf(0f to 0f, -30f to 0f, 30f to 0f, 0f to 20f)) for (yaw in listOf(0f, 90f, -135f)) {
            val h = head(pitch, yaw, roll)
            val p = PanelPlace.placeUpright(h, PanelPlace.ROOM_PANEL)
            // upright: the pose is a yaw only, so the panel's up is the world's up
            val up = p.rotation.rotate(Vec3.UP)
            assertTrue(up.dot(Vec3.UP) > 0.9998f)
            val to = p.position - h.position
            assertEquals(0.9f, to.length, 1e-3f)
            val drop = Math.toDegrees(asin((-to.y / to.length).toDouble())).toFloat()
            assertEquals("drop at pitch $pitch yaw $yaw", 15f, drop, 0.5f)
            // 20° left of the level heading
            val heading = Math.toRadians(yaw.toDouble())
            val ahead = Vec3(-kotlin.math.sin(heading).toFloat(), 0f, -kotlin.math.cos(heading).toFloat())
            val flat = Vec3(to.x, 0f, to.z).normalized()
            val ang = Math.toDegrees(kotlin.math.acos(flat.dot(ahead).coerceIn(-1f, 1f).toDouble())).toFloat()
            assertEquals(20f, ang, 0.5f)
            assertTrue("left of the view", ahead.cross(flat).y > 0f)
            // faces the eye: the panel's +Z points back towards the head (horizontally)
            val normal = p.rotation.rotate(Vec3(0f, 0f, 1f))
            val back = Vec3(-to.x, 0f, -to.z).normalized()
            assertTrue(normal.dot(back) > 0.999f)
            assertTrue(hypot(to.x, to.z) > 0.5f)
        }
    }

    @Test
    fun settleGateWaitsForAStillHead() {
        // D29: the press happens while looking at the button; the panel is placed once the head has been still 0.5 s
        val g = SettleGate()
        g.ask(0)
        assertFalse(g.placeNow(0, head(0f, 0f)))
        assertFalse(g.placeNow(200, head(0f, 20f)))
        assertFalse(g.placeNow(400, head(0f, 40f)))
        assertFalse(g.placeNow(800, head(0f, 41f)))
        assertTrue(g.placeNow(950, head(0f, 41f)))
        assertFalse(g.placeNow(2000, head(0f, 41f)))
    }

    @Test
    fun settledPlacementFollowsTheFinalHeading() {
        // 「看着按钮按下再转正前方」: the panel goes where the head points once still, < 2° off
        val g = SettleGate()
        g.ask(0)
        var t = 0L
        var last = head(-25f, 30f)
        g.placeNow(t, last)
        for (yaw in listOf(25f, 15f, 5f, 0f)) {
            t += 100
            last = head(-10f, yaw)
            g.placeNow(t, last)
        }
        while (!g.placeNow(t, last)) t += 50
        val p = PanelPlace.placeUpright(last, PanelPlace.ROOM_PANEL)
        val to = p.position - last.position
        val yaw = Math.toDegrees(kotlin.math.atan2(-to.x.toDouble(), -to.z.toDouble())).toFloat()
        assertEquals(20f, yaw, 2f)
    }
}
