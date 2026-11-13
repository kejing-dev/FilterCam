package io.embodit.filtercam.domain

/** Date stamp format (design P9 设置: ’26 11 4 | 2026.11.04 | 不显示). */
enum class StampFormat(val id: String) {
    SHORT("short"),
    DOTTED("dotted"),
    OFF("off");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SHORT
    }
}

/**
 * The date stamp drawn into the picture: a seven-segment font of our own (design content.inventory.fonts), so the stamp
 * in the viewfinder, the saved photo and the room-mode quad are the same bitmap. Pure Kotlin: a coverage mask in 0..1.
 */
object Stamp {
    /** Text for a date ([year] full, [month] 1..12, [day] 1..31) in [format]; null for [StampFormat.OFF]. */
    fun text(format: StampFormat, year: Int, month: Int, day: Int): String? =
        when (format) {
            StampFormat.SHORT -> String.format(java.util.Locale.US, "'%02d %d %d", year % 100, month, day)
            StampFormat.DOTTED -> String.format(java.util.Locale.US, "%04d.%02d.%02d", year, month, day)
            StampFormat.OFF -> null
        }

    // segments a b c d e f g (top, top right, bottom right, bottom, bottom left, top left, middle)
    private val DIGITS =
        mapOf(
            '0' to "abcdef",
            '1' to "bc",
            '2' to "abged",
            '3' to "abgcd",
            '4' to "fgbc",
            '5' to "afgcd",
            '6' to "afgedc",
            '7' to "abc",
            '8' to "abcdefg",
            '9' to "abcfgd",
            '-' to "g",
        )

    /** Width of [text] in pixels at digit height [h] (digits 0.6 h wide, gaps 0.22 h; '.' and '\'' narrow, space 0.35 h). */
    fun width(text: String, h: Int): Int = text.sumOf { advance(it, h) }

    private fun advance(c: Char, h: Int): Int =
        when (c) {
            ' ' -> (h * 0.35f).toInt()
            '.', '\'' -> (h * 0.3f).toInt()
            else -> (h * 0.82f).toInt()
        }

    /** A coverage mask ([w] x [h] floats, 0..1) of [text] with digit height [digitH], plus [pad] pixels around it. */
    fun mask(text: String, digitH: Int, pad: Int): Mask {
        val w = width(text, digitH) + pad * 2
        val h = digitH + pad * 2
        val m = FloatArray(w * h)
        val t = (digitH * 0.13f).coerceAtLeast(1.5f) // stroke
        var x = pad.toFloat()
        val y0 = pad.toFloat()
        fun rect(x0: Float, y0r: Float, x1: Float, y1: Float) {
            val ix0 = x0.toInt().coerceAtLeast(0)
            val ix1 = kotlin.math.ceil(x1).toInt().coerceAtMost(w)
            val iy0 = y0r.toInt().coerceAtLeast(0)
            val iy1 = kotlin.math.ceil(y1).toInt().coerceAtMost(h)
            for (yy in iy0 until iy1) for (xx in ix0 until ix1) {
                // soft edges: coverage of the pixel by the rectangle
                val cx = (minOf(xx + 1f, x1) - maxOf(xx.toFloat(), x0)).coerceIn(0f, 1f)
                val cy = (minOf(yy + 1f, y1) - maxOf(yy.toFloat(), y0r)).coerceIn(0f, 1f)
                val k = yy * w + xx
                m[k] = maxOf(m[k], cx * cy)
            }
        }
        for (c in text) {
            val dw = digitH * 0.6f
            val segs = DIGITS[c]
            if (segs != null) {
                val g = t * 0.35f // gap between segments
                val mid = y0 + digitH / 2f
                for (s in segs) {
                    when (s) {
                        'a' -> rect(x + g, y0, x + dw - g, y0 + t)
                        'd' -> rect(x + g, y0 + digitH - t, x + dw - g, y0 + digitH)
                        'g' -> rect(x + g, mid - t / 2, x + dw - g, mid + t / 2)
                        'f' -> rect(x, y0 + g, x + t, mid - g)
                        'b' -> rect(x + dw - t, y0 + g, x + dw, mid - g)
                        'e' -> rect(x, mid + g, x + t, y0 + digitH - g)
                        'c' -> rect(x + dw - t, mid + g, x + dw, y0 + digitH - g)
                    }
                }
            } else if (c == '.') {
                rect(x + digitH * 0.05f, y0 + digitH - t, x + digitH * 0.05f + t, y0 + digitH)
            } else if (c == '\'') {
                rect(x + digitH * 0.05f, y0, x + digitH * 0.05f + t * 0.8f, y0 + digitH * 0.3f)
            }
            x += advance(c, digitH)
        }
        return Mask(w, h, m)
    }

    class Mask(val w: Int, val h: Int, val a: FloatArray)
}
