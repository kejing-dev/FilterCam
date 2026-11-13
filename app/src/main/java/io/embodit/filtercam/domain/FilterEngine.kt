package io.embodit.filtercam.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An ARGB picture (Android `Bitmap.getPixels` layout), plain JVM so the engine is unit tested. */
class Img(val w: Int, val h: Int, val px: IntArray) {
    init {
        require(px.size == w * h) { "pixels ${px.size} != $w x $h" }
    }

    /** Bilinear resize to [nw] x [nh]. */
    fun resized(nw: Int, nh: Int): Img {
        if (nw == w && nh == h) return this
        val out = IntArray(nw * nh)
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
            val dy = fy - y0
            val y1 = min(y0 + 1, h - 1)
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
                val dx = fx - x0
                val x1 = min(x0 + 1, w - 1)
                val a = px[y0 * w + x0]
                val b = px[y0 * w + x1]
                val c = px[y1 * w + x0]
                val d = px[y1 * w + x1]
                var o = 0xFF shl 24
                for (sh in intArrayOf(16, 8, 0)) {
                    val top = ((a shr sh) and 0xFF) * (1 - dx) + ((b shr sh) and 0xFF) * dx
                    val bot = ((c shr sh) and 0xFF) * (1 - dx) + ((d shr sh) and 0xFF) * dx
                    o = o or ((top * (1 - dy) + bot * dy).roundToInt().coerceIn(0, 255) shl sh)
                }
                out[y * nw + x] = o
            }
        }
        return Img(nw, nh, out)
    }

    /** Centre crop to the aspect [aw] : [ah]. */
    fun cropAspect(aw: Int, ah: Int): Img {
        val target = aw.toFloat() / ah
        val cur = w.toFloat() / h
        if (kotlin.math.abs(cur - target) < 1e-3f) return this
        val (cw, ch) = if (cur > target) (h * target).roundToInt() to h else w to (w / target).roundToInt()
        val x0 = (w - cw) / 2
        val y0 = (h - ch) / 2
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(px, (y + y0) * w + x0, out, y * cw, cw)
        return Img(cw, ch, out)
    }

    /** Centre crop 4 : 3, then scale to [width] wide (design S09: 长边缩到 1024，居中裁 4:3; portrait keeps its middle band). */
    fun fitFourThree(width: Int): Img = cropAspect(4, 3).resized(width, width * 3 / 4)
}

/** What goes onto one picture besides the filter: the date stamp text (null = none) and the per-shot random seed. */
data class Look(
    val filter: FilterDef,
    val lut: Lut3D?,
    /** 0..1 (design T2 强度). */
    val strength: Float,
    /** Random phase of grain / noise / leak (design M5: random per shot, fixed while viewing). */
    val seed: Int,
    val stampText: String?,
    /** 「原样」: the source untouched (design T6, params all 0). */
    val compare: Boolean = false,
)

/**
 * The filter engine (design techRoute pipelines, F1a: the app runs the effect chain itself). One function drives the
 * preview box, the card thumbnails, the viewfinder's photo sources and every saved photo, so "what you see in the
 * window is what you get" (design M4). Order: low-res → LUT → lift / crush → sharpen → soft focus → blocks → halation →
 * flash → leak → vignette → scanlines → grain / noise → strength mix → square + instant frame → date stamp.
 */
object FilterEngine {
    fun render(src: Img, look: Look): Img {
        if (look.compare) return src
        val p = look.filter.params
        val w = src.w
        val h = src.h
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val c = src.px[i]
            r[i] = ((c shr 16) and 0xFF) / 255f
            g[i] = ((c shr 8) and 0xFF) / 255f
            b[i] = (c and 0xFF) / 255f
        }
        val r0 = r.copyOf()
        val g0 = g.copyOf()
        val b0 = b.copyOf()
        val unit = w / 640f // effect sizes are designed at 640 px wide

        val ds = (p.downsample * unit).roundToInt()
        if (p.downsample > 1 && ds > 1) for (ch in arrayOf(r, g, b)) pixelate(ch, w, h, ds)

        look.lut?.let { lut ->
            val tmp = FloatArray(3)
            val mix = p.lutMix
            for (i in 0 until n) {
                lut.apply(r[i], g[i], b[i], tmp)
                r[i] += (tmp[0] - r[i]) * mix
                g[i] += (tmp[1] - g[i]) * mix
                b[i] += (tmp[2] - b[i]) * mix
            }
        }

        if (p.lift > 0f || p.crush > 0f) {
            for (ch in arrayOf(r, g, b)) for (i in 0 until n) {
                var v = ch[i]
                if (p.crush > 0f) v = ((v - p.crush) / (1 - p.crush)).coerceAtLeast(0f)
                if (p.lift > 0f) v = p.lift + v * (1 - p.lift)
                ch[i] = v
            }
        }

        if (p.sharpen > 0f) {
            val rad = max(1, unit.roundToInt())
            for (ch in arrayOf(r, g, b)) {
                val bl = boxBlur(ch, w, h, rad)
                for (i in 0 until n) ch[i] += (ch[i] - bl[i]) * p.sharpen * 1.6f
            }
        }

        if (p.softBlur > 0f) {
            val rad = max(1, (3 * unit).roundToInt())
            for (ch in arrayOf(r, g, b)) {
                val bl = boxBlur(boxBlur(ch, w, h, rad), w, h, rad)
                for (i in 0 until n) ch[i] += (bl[i] - ch[i]) * p.softBlur * 1.5f
            }
        }

        if (p.blockiness > 0f) {
            val bs = max(2, (8 * unit).roundToInt())
            for (ch in arrayOf(r, g, b)) {
                val blocks = ch.copyOf()
                pixelate(blocks, w, h, bs)
                // JPEG-ish: flat blocks plus a faint ring at block edges
                for (i in 0 until n) {
                    val x = i % w
                    val y = i / w
                    val edge = if (x % bs == 0 || y % bs == 0) 0.03f else 0f
                    ch[i] += (blocks[i] - ch[i]) * p.blockiness * 0.55f - edge * p.blockiness
                }
            }
        }

        if (p.halation > 0f) {
            val bright = FloatArray(n)
            val rad = max(2, (10 * p.halationRadius * unit).roundToInt())
            val warm = floatArrayOf(1f, 0.55f, 0.35f)
            val neon = p.halationRadius > 1.5f // the neon filter keeps the light's own colour (F07)
            for ((k, ch) in arrayOf(r, g, b).withIndex()) {
                for (i in 0 until n) {
                    val l = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                    val t = ((l - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    bright[i] = t * (if (neon) ch[i] else warm[k] * l)
                }
                val bl = boxBlur(boxBlur(boxBlur(bright, w, h, rad), w, h, rad), w, h, rad)
                for (i in 0 until n) ch[i] += bl[i] * p.halation * 1.1f
            }
        }

        val cx = w / 2f
        val cy = h / 2f
        val diag = sqrt(cx * cx + cy * cy)
        if (p.flashCenter > 0f) {
            for (i in 0 until n) {
                val dx = (i % w - cx) / cx
                val dy = (i / w - cy) / cy
                val k = 1f + p.flashCenter * 1.2f * (1f - (dx * dx + dy * dy) / 2f).coerceIn(0f, 1f).let { it * it }
                r[i] *= k
                g[i] *= k
                b[i] *= k
            }
        }

        if (p.leak > 0f) {
            val preset = Math.floorMod(look.seed, LEAKS.size)
            val lk = LEAKS[preset]
            for (i in 0 until n) {
                val x = (i % w).toFloat() / w
                val y = (i / w).toFloat() / h
                val dx = x - lk[0]
                val dy = (y - lk[1]) * h / w
                val d = sqrt(dx * dx + dy * dy) / lk[2]
                val a = ((1f - d).coerceIn(0f, 1f)).let { it * it * (3 - 2 * it) } * p.leak
                // orange at the source, magenta further out (design F04: 橙→品红渐变光)
                val t = d.coerceIn(0f, 1f)
                val lr = 1f
                val lg = 0.55f * (1 - t) + 0.15f * t
                val lb = 0.1f * (1 - t) + 0.55f * t
                r[i] = 1 - (1 - r[i]) * (1 - lr * a)
                g[i] = 1 - (1 - g[i]) * (1 - lg * a)
                b[i] = 1 - (1 - b[i]) * (1 - lb * a)
            }
        }

        if (p.vignette > 0f) {
            for (i in 0 until n) {
                val dx = i % w - cx
                val dy = i / w - cy
                val d = sqrt(dx * dx + dy * dy) / diag
                val t = ((d - 0.45f) / 0.55f).coerceIn(0f, 1f)
                val k = 1f - p.vignette * 1.6f * t * t
                r[i] *= k
                g[i] *= k
                b[i] *= k
            }
        }

        if (p.scanlines > 0f) {
            val period = max(2, (3 * unit).roundToInt())
            for (i in 0 until n) if ((i / w) % period == 0) {
                val k = 1f - p.scanlines * 0.9f
                r[i] *= k
                g[i] *= k
                b[i] *= k
            }
        }

        if (p.grain > 0f || p.noise > 0f || p.chromaNoise > 0f) {
            val cell = max(1, (p.grainScale * unit).roundToInt())
            val seed = look.seed
            for (i in 0 until n) {
                val x = i % w
                val y = i / w
                if (p.grain > 0f) {
                    val l = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                    val mid = 0.35f + 2.6f * l * (1 - l) // grain shows most in the mid-tones
                    val v = gauss(x / cell, y / cell, seed) * p.grain * 0.16f * mid
                    r[i] += v
                    g[i] += v
                    b[i] += v
                }
                if (p.noise > 0f) {
                    val v = gauss(x, y, seed + 7) * p.noise * 0.10f
                    r[i] += v + gauss(x, y, seed + 11) * p.noise * 0.05f
                    g[i] += v
                    b[i] += v + gauss(x, y, seed + 13) * p.noise * 0.06f
                }
                if (p.chromaNoise > 0f) {
                    val cc = max(1, (2 * unit).roundToInt())
                    r[i] += gauss(x / cc, y / cc, seed + 17) * p.chromaNoise * 0.07f
                    b[i] += gauss(x / cc, y / cc, seed + 19) * p.chromaNoise * 0.09f
                }
            }
        }

        val s = look.strength.coerceIn(0f, 1f)
        val out = IntArray(n)
        for (i in 0 until n) {
            val rr = r0[i] + (r[i] - r0[i]) * s
            val gg = g0[i] + (g[i] - g0[i]) * s
            val bb = b0[i] + (b[i] - b0[i]) * s
            out[i] = argb(rr, gg, bb)
        }
        var img = Img(w, h, out)
        if (p.polaroid) img = instantFrame(img)
        look.stampText?.let { img = stamp(img, it, p.stamp.takeIf { s -> s != StampStyle.NONE } ?: StampStyle.SEVEN_SEG, p.stampGlow) }
        return img
    }

    /** Light-leak presets (x, y of the source in 0..1, reach): right edge, top, top right, left (design F04 四套预设). */
    val LEAKS = arrayOf(floatArrayOf(1.05f, 0.45f, 0.75f), floatArrayOf(0.5f, -0.1f, 0.7f), floatArrayOf(1.0f, 0.0f, 0.8f), floatArrayOf(-0.05f, 0.6f, 0.7f))

    /** Stamp colour #FF7A1A (design palette.stamp). */
    const val STAMP_ARGB = 0xFFFF7A1A.toInt()

    /**
     * Instant square (design F08): centre square of the picture inside a white frame, wider at the bottom. The output is
     * square with the picture's height as its side.
     */
    fun instantFrame(src: Img): Img {
        val side = min(src.w, src.h)
        val sq = src.cropAspect(1, 1)
        val border = (side * 0.06f).roundToInt()
        val bottom = (side * 0.2f).roundToInt()
        val inner = side - border * 2
        val innerH = side - border - bottom
        val pic = sq.resized(inner, innerH.coerceAtLeast(1)).let { it }
        val out = IntArray(side * side) { FRAME_ARGB }
        for (y in 0 until pic.h) System.arraycopy(pic.px, y * pic.w, out, (y + border) * side + border, pic.w)
        return Img(side, side, out)
    }

    const val FRAME_ARGB = 0xFFF6F3EC.toInt()

    /**
     * The orange seven-segment date with a small glow (design F06: 右下角橙红色七段日期，带一点晕). [style] SMALL is the
     * F09 small date at the bottom left.
     */
    fun stamp(src: Img, text: String, style: StampStyle, glow: Float): Img {
        val small = style == StampStyle.SMALL
        val digitH = max(8, (src.h * (if (small) 0.035f else 0.055f)).roundToInt())
        val pad = max(2, digitH / 2)
        val m = Stamp.mask(text, digitH, pad)
        val glowMask = boxBlur(boxBlur(m.a, m.w, m.h, max(1, digitH / 6)), m.w, m.h, max(1, digitH / 6))
        val margin = (src.h * 0.05f).roundToInt()
        val x0 = if (small) margin else src.w - m.w - margin
        val y0 = src.h - m.h - margin
        val out = src.px.copyOf()
        val sr = ((STAMP_ARGB shr 16) and 0xFF) / 255f
        val sg = ((STAMP_ARGB shr 8) and 0xFF) / 255f
        val sb = (STAMP_ARGB and 0xFF) / 255f
        for (y in 0 until m.h) for (x in 0 until m.w) {
            val tx = x0 + x
            val ty = y0 + y
            if (tx !in 0 until src.w || ty !in 0 until src.h) continue
            val a = m.a[y * m.w + x]
            val gl = glowMask[y * m.w + x] * glow * 1.4f
            if (a <= 0f && gl <= 0f) continue
            val c = out[ty * src.w + tx]
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f
            // glow adds light (a small bulb projected onto the film), the digits themselves cover
            r = 1 - (1 - r) * (1 - sr * gl)
            g = 1 - (1 - g) * (1 - sg * gl * 0.7f)
            b = 1 - (1 - b) * (1 - sb * gl * 0.5f)
            r += (sr - r) * a
            g += (sg - g) * a
            b += (sb - b) * a
            out[ty * src.w + tx] = argb(r, g, b)
        }
        return Img(src.w, src.h, out)
    }

    // ---------------------------------------------------------------------------------------------

    fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Mean of [s] x [s] blocks written back over each block (nearest-neighbour upscale of a downscale). */
    fun pixelate(ch: FloatArray, w: Int, h: Int, s: Int) {
        var by = 0
        while (by < h) {
            var bx = 0
            val ye = min(by + s, h)
            while (bx < w) {
                val xe = min(bx + s, w)
                var sum = 0f
                for (y in by until ye) for (x in bx until xe) sum += ch[y * w + x]
                val m = sum / ((ye - by) * (xe - bx))
                for (y in by until ye) for (x in bx until xe) ch[y * w + x] = m
                bx += s
            }
            by += s
        }
    }

    /** Separable box blur with radius [r] (clamped edges). */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc / win
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / win
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Hash noise roughly N(0, 1) (sum of two uniforms, scaled), stable for (x, y, seed). */
    fun gauss(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) + hash(x, y, seed xor 0x5bd1e995.toInt()) - 1f) * 2.4f

    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }
}
