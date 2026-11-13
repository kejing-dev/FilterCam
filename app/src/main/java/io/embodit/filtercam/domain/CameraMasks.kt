package io.embodit.filtercam.domain

import kotlin.math.sqrt

/**
 * The live camera source in the viewfinder (headset only): the app never sees those pixels, so the filter runs inside the
 * SpatialML pipeline as operators (design fallback F1a / techRoute ④, the same graph shape Low Vision Walk shipped):
 *
 *     out_c = keep_c * src_c + greyW_c * grey + add_c          (per pixel, per channel; grey = RGB → GRAY → RGB)
 *
 * The masks approximate the filter: the LUT as a per-channel gain + offset fitted on a grey ramp plus a saturation
 * factor, vignette, flash, leak, lift / crush, the instant frame and the date stamp. Grain, blur, blocks and halation are
 * not in this approximation (no per-frame noise upload, design M2). Saved camera photos read the raw frame back and run
 * the full [FilterEngine], so the saved picture is the full look. Layout: float [h, w * 3], RGB interleaved.
 */
class CameraMasks(val w: Int, val h: Int, val keep: FloatArray, val greyW: FloatArray, val add: FloatArray) {
    companion object {
        /** Saturation factor of each built-in LUT (1 = unchanged, 0 = black and white). */
        private val SAT = mapOf("gold200" to 1.1f, "ccd_cool" to 1.15f, "trix" to 0f, "cyber_split" to 0.9f, "instant" to 0.82f, "y2k_cool" to 1.2f)

        fun of(look: Look, w: Int, h: Int): CameraMasks {
            val n = w * h * 3
            val keep = FloatArray(n) { 1f }
            val greyW = FloatArray(n)
            val add = FloatArray(n)
            if (look.compare) return CameraMasks(w, h, keep, greyW, add)
            val p = look.filter.params
            val s = look.strength.coerceIn(0f, 1f)
            // LUT fit: out ≈ a * in + b per channel, on grey 0.2 / 0.8
            val a = FloatArray(3) { 1f }
            val b = FloatArray(3)
            look.lut?.let { lut ->
                val lo = FloatArray(3)
                val hi = FloatArray(3)
                lut.apply(0.2f, 0.2f, 0.2f, lo)
                lut.apply(0.8f, 0.8f, 0.8f, hi)
                for (c in 0 until 3) {
                    val ac = (hi[c] - lo[c]) / 0.6f
                    val bc = lo[c] - ac * 0.2f
                    a[c] = 1f + (ac - 1f) * p.lutMix
                    b[c] = bc * p.lutMix
                }
            }
            val sat = 1f + ((SAT[p.lut] ?: 1f) - 1f) * p.lutMix
            val cx = w / 2f
            val cy = h / 2f
            val diag = sqrt(cx * cx + cy * cy)
            val leak = if (p.leak > 0f) FilterEngine.LEAKS[Math.floorMod(look.seed, FilterEngine.LEAKS.size)] else null
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) / diag
                val vt = ((d - 0.45f) / 0.55f).coerceIn(0f, 1f)
                var gain = 1f - p.vignette * 1.6f * vt * vt
                if (p.flashCenter > 0f) {
                    val fx = dx / cx
                    val fy = dy / cy
                    gain *= 1f + p.flashCenter * 1.2f * (1f - (fx * fx + fy * fy) / 2f).coerceIn(0f, 1f).let { it * it }
                }
                var la = 0f
                if (leak != null) {
                    val lx = x.toFloat() / w - leak[0]
                    val ly = (y.toFloat() / h - leak[1]) * h / w
                    val ld = sqrt(lx * lx + ly * ly) / leak[2]
                    la = (1f - ld).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } * p.leak * 0.8f
                }
                for (c in 0 until 3) {
                    var k = sat * a[c]
                    var gw = (1f - sat) * a[c]
                    var ad = b[c]
                    // crush then lift, as affine maps on the result
                    if (p.crush > 0f) {
                        val m = 1f / (1f - p.crush)
                        k *= m
                        gw *= m
                        ad = (ad - p.crush) * m
                    }
                    if (p.lift > 0f) {
                        k *= 1f - p.lift
                        gw *= 1f - p.lift
                        ad = p.lift + ad * (1f - p.lift)
                    }
                    k *= gain
                    gw *= gain
                    ad *= gain
                    if (la > 0f) {
                        val lc = when (c) { 0 -> 1f; 1 -> 0.45f; else -> 0.25f }
                        ad += la * lc
                        k *= 1f - la * lc * 0.5f
                        gw *= 1f - la * lc * 0.5f
                    }
                    val i = (y * w + x) * 3 + c
                    keep[i] = 1f + (k - 1f) * s
                    greyW[i] = gw * s
                    add[i] = ad * s * 255f // the pipeline works on 0..255 values
                }
            }
            val masks = CameraMasks(w, h, keep, greyW, add)
            if (p.polaroid) masks.frame()
            look.stampText?.let { masks.stamp(it, p.stamp == StampStyle.SMALL) }
            return masks
        }
    }

    /** Instant frame: a white border (the picture keeps the middle square, as in the saved photo). */
    private fun frame() {
        val side = h
        val x0 = (w - side) / 2
        val border = (side * 0.06f).toInt()
        val bottom = (side * 0.2f).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            val inPic = x >= x0 + border && x < x0 + side - border && y >= border && y < side - bottom
            if (inPic) continue
            val inFrame = x >= x0 && x < x0 + side
            val col = if (inFrame) FilterEngine.FRAME_ARGB else 0xFF101010.toInt()
            for (c in 0 until 3) {
                val i = (y * w + x) * 3 + c
                keep[i] = 0f
                greyW[i] = 0f
                add[i] = ((col shr (16 - 8 * c)) and 0xFF).toFloat()
            }
        }
    }

    /** The orange digits over the picture, bottom right (bottom left for [small]), like [FilterEngine.stamp]. */
    private fun stamp(text: String, small: Boolean) {
        val digitH = maxOf(8, (h * (if (small) 0.035f else 0.055f)).toInt())
        val pad = maxOf(2, digitH / 2)
        val m = Stamp.mask(text, digitH, pad)
        val margin = (h * 0.05f).toInt()
        val x0 = if (small) margin else w - m.w - margin
        val y0 = h - m.h - margin
        val col = intArrayOf(0xFF, 0x7A, 0x1A)
        for (y in 0 until m.h) for (x in 0 until m.w) {
            val tx = x0 + x
            val ty = y0 + y
            if (tx !in 0 until w || ty !in 0 until h) continue
            val a = m.a[y * m.w + x]
            if (a <= 0f) continue
            for (c in 0 until 3) {
                val i = (ty * w + tx) * 3 + c
                keep[i] *= 1 - a
                greyW[i] *= 1 - a
                add[i] = add[i] * (1 - a) + col[c] * a
            }
        }
    }
}
