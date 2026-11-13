package io.embodit.filtercam.domain

/** How much of a filter survives in room mode (design filters.roomModeLegend). */
enum class RoomMode { FULL, PARTIAL, NONE }

/** Date stamp look (design F02 / F06 / F09). */
enum class StampStyle { NONE, SEVEN_SEG, SMALL }

/** Shutter sound set (design sounds.list, v1: three sets mapped by filter). */
enum class ShutterSound { MECHANICAL, ELECTRONIC, INSTANT }

/**
 * Effect parameters of one filter (design filters.list[].params). All amounts are 0..1 unless noted; every value at its
 * default leaves the picture as it is, so [FilterParams.IDENTITY] is the 「原样」 comparison (design T6).
 */
data class FilterParams(
    val lut: String? = null,
    val lutMix: Float = 1f,
    val grain: Float = 0f,
    val grainScale: Float = 1f,
    val vignette: Float = 0f,
    val halation: Float = 0f,
    val halationRadius: Float = 1f,
    val lift: Float = 0f,
    val crush: Float = 0f,
    val sharpen: Float = 0f,
    val blockiness: Float = 0f,
    val chromaNoise: Float = 0f,
    val flashCenter: Float = 0f,
    val leak: Float = 0f,
    val softBlur: Float = 0f,
    val scanlines: Float = 0f,
    /** Pixel size of the low-resolution look (1 = off; F09 「降到 320 x 240 再最近邻放大」 at 640 px is 2). */
    val downsample: Int = 1,
    val noise: Float = 0f,
    val polaroid: Boolean = false,
    val stamp: StampStyle = StampStyle.NONE,
    val stampGlow: Float = 0f,
) {
    companion object {
        val IDENTITY = FilterParams()
    }
}

/**
 * One filter card (design filters.list). Names, taglines and the shot line live in string resources keyed by [id];
 * [available] = false is a v1.1 card shown with its badge (F03 / F10). The fisheye card was dropped at gate B
 * (2026-11-05, user: 「鱼眼 不用」): it is not in the list at all.
 */
data class FilterDef(
    val id: String,
    val params: FilterParams,
    val roomMode: RoomMode,
    val sound: ShutterSound,
    val available: Boolean = true,
    /** Design Q3 default: only the date-stamp filters start with the stamp on. */
    val stampByDefault: Boolean = params.stamp != StampStyle.NONE,
    /** Which sample photo shows this filter best on its card (design content.inventory.samplePhotos). */
    val cardSample: String = "street",
) {
    val isLut: Boolean
        get() = id.startsWith(LUT_PREFIX)

    val square: Boolean
        get() = params.polaroid

    companion object {
        const val LUT_PREFIX = "LUT:"
    }
}

object Filters {
    val F01 = FilterDef("F01", FilterParams(lut = "gold200", grain = 0.35f, vignette = 0.15f, halation = 0.10f, lift = 0.04f), RoomMode.PARTIAL, ShutterSound.MECHANICAL, cardSample = "street")
    val F02 =
        FilterDef(
            "F02",
            FilterParams(lut = "ccd_cool", sharpen = 0.6f, blockiness = 0.4f, chromaNoise = 0.3f, flashCenter = 0.15f, stamp = StampStyle.SEVEN_SEG, stampGlow = 0.3f),
            RoomMode.NONE,
            ShutterSound.ELECTRONIC,
            cardSample = "cat",
        )
    val F04 = FilterDef("F04", FilterParams(lut = "gold200", leak = 0.6f, grain = 0.3f, vignette = 0.10f), RoomMode.FULL, ShutterSound.MECHANICAL, cardSample = "window")
    val F05 = FilterDef("F05", FilterParams(lut = "trix", grain = 0.6f, grainScale = 1.6f, vignette = 0.25f, crush = 0.06f), RoomMode.NONE, ShutterSound.MECHANICAL, cardSample = "street")
    val F06 = FilterDef("F06", FilterParams(lut = "gold200", lutMix = 0.5f, grain = 0.3f, stamp = StampStyle.SEVEN_SEG, stampGlow = 0.4f), RoomMode.FULL, ShutterSound.MECHANICAL, cardSample = "desk")
    val F07 = FilterDef("F07", FilterParams(lut = "cyber_split", halation = 0.35f, halationRadius = 2f, scanlines = 0.15f), RoomMode.PARTIAL, ShutterSound.ELECTRONIC, cardSample = "night")
    val F08 = FilterDef("F08", FilterParams(lut = "instant", lift = 0.14f, softBlur = 0.3f, vignette = 0.08f, polaroid = true), RoomMode.PARTIAL, ShutterSound.INSTANT, cardSample = "food")
    val F09 =
        FilterDef(
            "F09",
            FilterParams(lut = "y2k_cool", downsample = 2, blockiness = 0.7f, noise = 0.4f, flashCenter = 0.25f, stamp = StampStyle.SMALL, stampGlow = 0.2f),
            RoomMode.NONE,
            ShutterSound.ELECTRONIC,
            cardSample = "cat",
        )

    /** v1.1 card (design F10, H5): visible with its badge, not usable. */
    val F10 = FilterDef("F10", FilterParams.IDENTITY, RoomMode.PARTIAL, ShutterSound.ELECTRONIC, available = false, stampByDefault = true, cardSample = "night")

    /** The built-in cards in strip order (design: 8 usable v1 filters; F03 fisheye dropped at gate B; F10 v1.1). */
    val builtIn: List<FilterDef> = listOf(F01, F02, F04, F05, F06, F07, F08, F09, F10)

    val usable: List<FilterDef> = builtIn.filter { it.available }

    /** Room mode cycle (design S15 / T12: F01 / F04 / F06 / F07 / F08). */
    val roomCycle: List<String> = builtIn.filter { it.available && it.roomMode != RoomMode.NONE }.map { it.id }

    /** What room mode switches to when the current filter has no room version (design S14). */
    const val ROOM_FALLBACK = "F04"

    fun lutFilter(name: String) = FilterDef(FilterDef.LUT_PREFIX + name, FilterParams(lut = FilterDef.LUT_PREFIX + name), RoomMode.NONE, ShutterSound.MECHANICAL, cardSample = "street")

    /** The filter for [id], including imported LUTs ("LUT:<name>"); null for an unknown id. */
    fun of(id: String, luts: Collection<String> = emptyList()): FilterDef? {
        builtIn.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith(FilterDef.LUT_PREFIX)) {
            val name = id.removePrefix(FilterDef.LUT_PREFIX)
            if (name in luts) return lutFilter(name)
        }
        return null
    }

    /** Next / previous room filter from [current] (wraps; a filter outside the cycle starts at its first entry). */
    fun roomStep(current: String, step: Int): String {
        val i = roomCycle.indexOf(current)
        if (i < 0) return roomCycle.first()
        return roomCycle[Math.floorMod(i + step, roomCycle.size)]
    }

    /** The card name shown for an imported LUT: the file name without extension, cut to 14 characters + 「…」 (design M8). */
    fun shortLutName(name: String): String = if (name.length <= LUT_NAME_MAX) name else name.take(LUT_NAME_MAX) + "…"

    const val LUT_NAME_MAX = 14
}
