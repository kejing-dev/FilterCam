package io.embodit.filtercam.domain

import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Room mode (design techRoute ③, entities E1–E3): what each filter keeps when it is spread over the whole passthrough
 * view. The overlays can only add light and colour (design copy.roomNote), so only the [RoomMode.FULL] / PARTIAL filters
 * have a room look.
 */
data class RoomLook(
    /** Veil tint ARGB without alpha, and its alpha share 0..1 (TRANSPARENT layer E1, α ≤ 0.35). */
    val tint: Int,
    val tintAlpha: Float,
    val vignette: Float,
    val grain: Float,
    val leak: Float,
    val neon: Float,
    val scanlines: Float,
    val frame: Boolean,
    val stamp: Boolean,
)

object RoomLooks {
    /** Veil plane: 10 x 10 m, 2 m ahead (design E1; covers ±68°). */
    const val VEIL_DIST_M = 2.0f
    const val VEIL_SIZE_M = 10f

    /** Grain plane: closer and smaller so a 1024² tile gives ~2–3 mm grains (design R1-M12 / N18). */
    const val GRAIN_DIST_M = 1.2f
    const val GRAIN_SIZE_M = 2.6f

    fun of(id: String): RoomLook? =
        when (id) {
            "F01" -> RoomLook(0xFFB070, 0.16f, 0.15f, 0.35f, 0f, 0f, 0f, frame = false, stamp = false)
            "F04" -> RoomLook(0xFFB070, 0.12f, 0.10f, 0.30f, 0.6f, 0f, 0f, frame = false, stamp = false)
            "F06" -> RoomLook(0xFFB878, 0.12f, 0.12f, 0.30f, 0f, 0f, 0f, frame = false, stamp = true)
            "F07" -> RoomLook(0x0E6E8E, 0.18f, 0.10f, 0f, 0f, 0.5f, 0.15f, frame = false, stamp = false)
            "F08" -> RoomLook(0xFFF4DC, 0.16f, 0.06f, 0.15f, 0f, 0f, 0f, frame = true, stamp = false)
            else -> null
        }

    /** Angle off the view axis (degrees) of a texel at (u, v) in 0..1 on a plane [size] m wide, [dist] m ahead. */
    fun angleDeg(u: Float, v: Float, size: Float, dist: Float): Float {
        val x = (u - 0.5f) * size
        val y = (v - 0.5f) * size
        return Math.toDegrees(atan(sqrt(x * x + y * y) / dist).toDouble()).toFloat()
    }

    /**
     * The veil texture (E1, TRANSPARENT): tint everywhere, darker towards the edge of the view (vignette from 15° to
     * 40°: the headset shows about ±45°), and the instant film's white frame outside ±30° × ±24° (wider below) (F08 「白相框 + 奶白纱」). ARGB, [px] square.
     */
    fun veil(look: RoomLook, px: Int): IntArray {
        val out = IntArray(px * px)
        val tr = (look.tint shr 16) and 0xFF
        val tg = (look.tint shr 8) and 0xFF
        val tb = look.tint and 0xFF
        for (y in 0 until px) for (x in 0 until px) {
            val u = (x + 0.5f) / px
            val v = (y + 0.5f) / px
            val deg = angleDeg(u, v, VEIL_SIZE_M, VEIL_DIST_M)
            val vig = ((deg - 15f) / 25f).coerceIn(0f, 1f).let { it * it }
            var a = look.tintAlpha
            var r = tr
            var g = tg
            var b = tb
            val dark = look.vignette * 1.8f * vig
            if (dark > 0f) {
                // mix towards black: alpha grows, colour darkens
                val na = a + (1 - a) * dark.coerceAtMost(0.8f)
                val k = a / na
                r = (r * k).toInt()
                g = (g * k).toInt()
                b = (b * k).toInt()
                a = na
            }
            if (look.frame) {
                val xd = Math.toDegrees(atan(((u - 0.5f) * VEIL_SIZE_M / VEIL_DIST_M).toDouble())).toFloat()
                val yd = Math.toDegrees(atan(((v - 0.5f) * VEIL_SIZE_M / VEIL_DIST_M).toDouble())).toFloat()
                // wider at the bottom (v grows downwards in the bitmap)
                if (xd < -30f || xd > 30f || yd < -24f || yd > 30f) {
                    r = 0xF6
                    g = 0xF3
                    b = 0xEC
                    a = 0.92f
                }
            }
            out[y * px + x] = ((a.coerceIn(0f, 0.95f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * The light layer (E2, ADD): the leak (orange → magenta from the right, about 30° off the view axis, [phase] 0..1 drifts it, design F04 6 s), the
     * neon halo (cyan left, magenta right, F07) and the scan lines. Black = adds nothing. ARGB opaque.
     */
    fun light(look: RoomLook, px: Int, phase: Float): IntArray {
        val out = IntArray(px * px)
        val p = phase * 2f * Math.PI.toFloat()
        val lx = 0.66f + 0.04f * kotlin.math.sin(p)
        val ly = 0.42f + 0.05f * kotlin.math.cos(p)
        for (y in 0 until px) for (x in 0 until px) {
            val u = (x + 0.5f) / px
            val v = (y + 0.5f) / px
            var r = 0f
            var g = 0f
            var b = 0f
            if (look.leak > 0f) {
                val dx = u - lx
                val dy = v - ly
                val d = sqrt(dx * dx + dy * dy) / 0.2f
                val a = (1 - d).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } * look.leak * 0.8f
                val t = d.coerceIn(0f, 1f)
                r += a
                g += a * (0.5f * (1 - t) + 0.12f * t)
                b += a * (0.08f * (1 - t) + 0.5f * t)
            }
            if (look.neon > 0f) {
                val left = ((0.42f - u) / 0.1f).coerceIn(0f, 1f)
                val right = ((u - 0.58f) / 0.1f).coerceIn(0f, 1f)
                r += (0.05f * left + 1.0f * right) * look.neon * 0.35f
                g += (0.45f * left + 0.25f * right) * look.neon * 0.35f
                b += (0.6f * left + 0.64f * right) * look.neon * 0.35f
            }
            if (look.scanlines > 0f && y % 6 == 0) {
                r += look.scanlines * 0.25f
                g += look.scanlines * 0.25f
                b += look.scanlines * 0.3f
            }
            out[y * px + x] = FilterEngine.argb(r, g, b)
        }
        return out
    }

    /** One grain phase (E2 grain, ADD): sparse bright specks, [seed] = phase (4 phases rotate at 4 Hz). */
    fun grain(amount: Float, px: Int, seed: Int): IntArray {
        val out = IntArray(px * px)
        for (y in 0 until px) for (x in 0 until px) {
            val v = (FilterEngine.gauss(x, y, seed) * amount * 0.09f).coerceAtLeast(0f)
            out[y * px + x] = FilterEngine.argb(v, v * 0.96f, v * 0.9f)
        }
        return out
    }

    /** The date stamp quad (E3, ADD): orange digits with glow on black, [w] x [h] px. */
    fun stamp(text: String, w: Int, h: Int): IntArray {
        val digitH = (h * 0.55f).toInt()
        val m = Stamp.mask(text, digitH, max(2, digitH / 4))
        val glow = FilterEngine.boxBlur(FilterEngine.boxBlur(m.a, m.w, m.h, max(1, digitH / 8)), m.w, m.h, max(1, digitH / 8))
        val out = IntArray(w * h) { 0xFF000000.toInt() }
        val x0 = w - m.w
        val y0 = (h - m.h) / 2
        for (y in 0 until m.h) for (x in 0 until m.w) {
            val tx = x0 + x
            val ty = y0 + y
            if (tx !in 0 until w || ty !in 0 until h) continue
            val a = (m.a[y * m.w + x] + glow[y * m.w + x] * 0.5f).coerceAtMost(1f)
            out[ty * w + tx] = FilterEngine.argb(a, a * 0x7A / 255f, a * 0x1A / 255f)
        }
        return out
    }
}
