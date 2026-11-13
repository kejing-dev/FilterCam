package io.embodit.filtercam.domain

/**
 * A 3D colour lookup table, [size]^3 entries of RGB in 0..1, red index fastest (the .cube order). Filters always run
 * on a [SIZE]^3 table: imported files of 17 / 33 / 65 are resampled to it (design filters.list LUT).
 */
class Lut3D(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3) { "LUT data ${data.size} != $size^3 x 3" }
    }

    /** Trilinear lookup of (r, g, b) in 0..1; writes the result into [out] (3 floats). */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = (r.coerceIn(0f, 1f) * n)
        val fg = (g.coerceIn(0f, 1f) * n)
        val fb = (b.coerceIn(0f, 1f) * n)
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val s1 = size
        val s2 = size * size
        for (c in 0 until 3) {
            fun at(ri: Int, gi: Int, bi: Int) = data[((bi * s2) + (gi * s1) + ri) * 3 + c]
            val c00 = at(r0, g0, b0) * (1 - dr) + at(r0 + 1, g0, b0) * dr
            val c10 = at(r0, g0 + 1, b0) * (1 - dr) + at(r0 + 1, g0 + 1, b0) * dr
            val c01 = at(r0, g0, b0 + 1) * (1 - dr) + at(r0 + 1, g0, b0 + 1) * dr
            val c11 = at(r0, g0 + 1, b0 + 1) * (1 - dr) + at(r0 + 1, g0 + 1, b0 + 1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            out[c] = c0 * (1 - db) + c1 * db
        }
    }

    /** Same table at [target]^3 entries (trilinear). */
    fun resampled(target: Int): Lut3D {
        if (target == size) return this
        val out = FloatArray(target * target * target * 3)
        val tmp = FloatArray(3)
        var k = 0
        for (b in 0 until target) for (g in 0 until target) for (r in 0 until target) {
            val n = (target - 1).toFloat()
            apply(r / n, g / n, b / n, tmp)
            out[k++] = tmp[0]
            out[k++] = tmp[1]
            out[k++] = tmp[2]
        }
        return Lut3D(target, out)
    }

    companion object {
        const val SIZE = 33

        fun identity(size: Int = SIZE): Lut3D {
            val out = FloatArray(size * size * size * 3)
            var k = 0
            val n = (size - 1).toFloat()
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                out[k++] = r / n
                out[k++] = g / n
                out[k++] = b / n
            }
            return Lut3D(size, out)
        }
    }
}

/** Why a .cube file was refused (design S11 「这个 .cube 读不了（只认 17 / 33 / 65 格）」). */
class CubeError(message: String) : Exception(message)

/**
 * Adobe / Resolve `.cube` reader: `LUT_3D_SIZE`, optional `DOMAIN_MIN` / `DOMAIN_MAX`, `TITLE`, comments, then N^3 rows
 * of "r g b" with red fastest. Only 17 / 33 / 65 grids are accepted (design T9); the table comes back at [Lut3D.SIZE].
 */
object CubeParser {
    val SIZES = setOf(17, 33, 65)

    fun parse(text: String): Lut3D {
        var size = 0
        var min = floatArrayOf(0f, 0f, 0f)
        var max = floatArrayOf(1f, 1f, 1f)
        var values: FloatArray? = null
        var n = 0
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val c = line[0]
            if (c.isLetter()) {
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw CubeError("bad LUT_3D_SIZE")
                        if (size !in SIZES) throw CubeError("grid $size not supported")
                        values = FloatArray(size * size * size * 3)
                    }
                    "LUT_1D_SIZE" -> throw CubeError("1D LUT not supported")
                    "DOMAIN_MIN" -> min = floats(parts)
                    "DOMAIN_MAX" -> max = floats(parts)
                    else -> Unit // TITLE, LUT_3D_INPUT_RANGE and other keywords are ignored
                }
                continue
            }
            val v = values ?: throw CubeError("data before LUT_3D_SIZE")
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) throw CubeError("bad row: $line")
            if (n + 3 > v.size) throw CubeError("too many rows")
            for (i in 0 until 3) {
                val f = parts[i].toFloatOrNull() ?: throw CubeError("bad number: ${parts[i]}")
                v[n++] = ((f - min[i]) / (max[i] - min[i]).let { if (it == 0f) 1f else it })
            }
        }
        val v = values ?: throw CubeError("no LUT_3D_SIZE")
        if (n != v.size) throw CubeError("rows ${n / 3} != $size^3")
        return Lut3D(size, v).resampled(Lut3D.SIZE)
    }

    private fun floats(parts: List<String>): FloatArray {
        if (parts.size < 4) throw CubeError("bad domain")
        return FloatArray(3) { parts[it + 1].toFloatOrNull() ?: throw CubeError("bad domain") }
    }
}
