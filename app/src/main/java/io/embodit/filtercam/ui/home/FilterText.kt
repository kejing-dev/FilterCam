package io.embodit.filtercam.ui.home

import io.embodit.filtercam.R
import io.embodit.filtercam.app.Samples
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.domain.RoomMode
import io.embodit.filtercam.ui.theme.Txt

/** Names, taglines and shot lines of the filters (design filters.list zh / en, copy.shotLines). */
object FilterText {
    private fun res(id: String, kind: Int): Int =
        when (id) {
            "F01" -> intArrayOf(R.string.f01_name, R.string.f01_tag, R.string.f01_line)
            "F02" -> intArrayOf(R.string.f02_name, R.string.f02_tag, R.string.f02_line)
            "F04" -> intArrayOf(R.string.f04_name, R.string.f04_tag, R.string.f04_line)
            "F05" -> intArrayOf(R.string.f05_name, R.string.f05_tag, R.string.f05_line)
            "F06" -> intArrayOf(R.string.f06_name, R.string.f06_tag, R.string.f06_line)
            "F07" -> intArrayOf(R.string.f07_name, R.string.f07_tag, R.string.f07_line)
            "F08" -> intArrayOf(R.string.f08_name, R.string.f08_tag, R.string.f08_line)
            "F09" -> intArrayOf(R.string.f09_name, R.string.f09_tag, R.string.f09_line)
            "F10" -> intArrayOf(R.string.f10_name, R.string.f10_tag, R.string.f10_line)
            else -> intArrayOf(R.string.lut_name, R.string.lut_tag, R.string.lut_line)
        }[kind]

    /** Card name; an imported LUT shows its file name cut to 14 characters (design M8). */
    fun name(t: Txt, id: String): String = if (id.startsWith(FilterDef.LUT_PREFIX)) Filters.shortLutName(id.removePrefix(FilterDef.LUT_PREFIX)) else t(res(id, 0))

    fun fullName(t: Txt, id: String): String = if (id.startsWith(FilterDef.LUT_PREFIX)) id.removePrefix(FilterDef.LUT_PREFIX) else t(res(id, 0))

    fun tagline(t: Txt, id: String): String = t(res(id, 1))

    fun shotLine(t: Txt, id: String): String = t(res(id, 2))

    fun roomMark(t: Txt, mode: RoomMode): String =
        when (mode) {
            RoomMode.FULL -> t(R.string.room_full)
            RoomMode.PARTIAL -> t(R.string.room_partial)
            RoomMode.NONE -> t(R.string.room_none)
        }

    fun roomNote(t: Txt, id: String): String? =
        when (id) {
            "F01" -> t(R.string.room_note_F01)
            "F04" -> t(R.string.room_note_F04)
            "F06" -> t(R.string.room_note_F06)
            "F07" -> t(R.string.room_note_F07)
            "F08" -> t(R.string.room_note_F08)
            else -> null
        }

    fun sample(t: Txt, name: String): String =
        t(
            when (name) {
                "desk" -> R.string.sample_desk
                "window" -> R.string.sample_window
                "cat" -> R.string.sample_cat
                "food" -> R.string.sample_food
                "night" -> R.string.sample_night
                else -> R.string.sample_street
            }
        )

    val samples = Samples.all
}
