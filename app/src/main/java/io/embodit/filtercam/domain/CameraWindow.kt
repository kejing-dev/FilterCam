package io.embodit.filtercam.domain

import kotlin.math.atan2

/**
 * The rules of the v0.2.0 camera window (design round 4, 2026-11-18 headset: "the photo is taken in a small side box",
 * "one window stacked on another"). Kept pure so they are unit tested; the store only asks.
 */

/** One permission's status tag on the permission card (design W1.pages.permission, S00 – S00c). */
enum class PermChip { OFF, ASKING, ON, REFUSED }

object PermCard {
    /** What the card's main button does. */
    enum class Action { START, ASK_AGAIN, SETTINGS }

    fun chip(p: CamPerm, missing: Set<CamPerm>, denied: Boolean, blocked: Set<CamPerm>, asking: CamPerm?): PermChip =
        when {
            asking == p -> PermChip.ASKING
            p !in missing -> PermChip.ON
            p in blocked || denied -> PermChip.REFUSED
            else -> PermChip.OFF
        }

    /** 1-based position in the asking order (Spatial Data 1/2, Camera 2/2). */
    fun step(p: CamPerm): Int = CamPerm.values().indexOf(p) + 1

    /** Never asked: 「开始授权」; refused and the system still asks: 「再问一次」; every missing one refused for good: 「去设置」. */
    fun action(prompt: CamPerms.Prompt): Action =
        when (prompt.kind) {
            CamPerms.Kind.NEED -> Action.START
            CamPerms.Kind.DENIED -> Action.ASK_AGAIN
            CamPerms.Kind.BLOCKED -> Action.SETTINGS
        }

    /** The one permission the card names in 「还差…」 after an answer: the first missing one in the asking order. */
    fun named(prompt: CamPerms.Prompt): CamPerm? = prompt.missing.firstOrNull()

    /** The order the dialogs come in: only the missing ones that the system will still ask for. */
    fun askOrder(missing: Set<CamPerm>, blocked: Set<CamPerm>): List<CamPerm> = CamPerm.values().filter { it in missing && it !in blocked }
}

/**
 * When the read-back session runs (design container.lifecycle): only for the camera source with both permissions, on a
 * device with a camera, while the main window is in front and room mode is off. Album / settings pause the loop and keep
 * the session (back to 「拍照」 within 0.5 s).
 */
object LensPolicy {
    enum class Want { CLOSED, OPEN, PAUSED }

    fun want(cameraSource: Boolean, permissionsOn: Boolean, hasCamera: Boolean, windowInFront: Boolean, inRoom: Boolean, failed: Boolean, onCameraPage: Boolean): Want =
        when {
            !cameraSource || !permissionsOn || !hasCamera || !windowInFront || inRoom || failed -> Want.CLOSED
            onCameraPage -> Want.OPEN
            else -> Want.PAUSED
        }

    /** S25: a lost session is reopened once by itself; the second loss in a row ends on the S21 card. */
    fun afterLoss(lossesInARow: Int): Boolean = lossesInARow <= 1
}

/** The filter side window W2 (design W2.visibility). */
object SideWindow {
    /**
     * Shown on the camera page when the user has not folded it, never over the permission card (S00 series, the first
     * picture opens it), never in room mode.
     */
    fun shown(wanted: Boolean, onCameraPage: Boolean, permissionCard: Boolean, inRoom: Boolean, quitting: Boolean): Boolean = wanted && onCameraPage && !permissionCard && !inRoom && !quitting
}

/** The album page (design W1.pages.album, S12 / S12e / S13): newest first, opens on the newest. */
object AlbumNav {
    /** After deleting the photo at [index] of [countBefore]: show the next one (the older neighbour), or the new last one. */
    fun afterDelete(index: Int, countBefore: Int): Int {
        val left = countBefore - 1
        return if (left <= 0) 0 else index.coerceAtMost(left - 1)
    }

    fun step(index: Int, by: Int, count: Int): Int = if (count == 0) 0 else (index + by).coerceIn(0, count - 1)

    /** The filter id in a saved name `FilterCam_<yyyyMMdd_HHmmss>_<F01|LUT_x>.jpg`, or null (raw originals, other files). */
    fun filterOf(name: String): String? {
        val m = Regex("""^FilterCam_\d{8}_\d{6}_(.+)\.jpg$""").find(name) ?: return null
        val id = m.groupValues[1]
        if (id == "raw") return null
        return if (id.startsWith("LUT_")) "LUT:" + id.removePrefix("LUT_") else id
    }

    /** Minutes since [takenMs] for the 「刚刚 / N 分钟前」 line; null after an hour (the date is shown then). */
    fun minutesAgo(takenMs: Long, nowMs: Long): Int? {
        val min = ((nowMs - takenMs).coerceAtLeast(0) / 60_000).toInt()
        return if (min >= 60) null else min
    }
}

/** At most three shots saving at once (design S08: the shutter can be pressed again while saving, queued up to 3). */
object ShotQueue {
    const val MAX = 3

    fun canShoot(saving: Int, flashing: Boolean): Boolean = saving < MAX && !flashing
}

/**
 * The two windows seen from the eye (design windowLayout): W1 600 x 576 dp at the system's main window spot, W2 280 x 576
 * dp at Placement.right(W1), same depth, the system gap between them. [mmPerDp] is the open question N25 (1.5 mm from the
 * v0.1.1 WorldSpace log, 1.74 mm measured by kinetica): both are checked.
 */
data class WindowLayout(val mmPerDp: Float, val gapM: Float, val distanceM: Float = 1.75f, val w1Dp: Int = 600, val w2Dp: Int = 280) {
    val w1RightM: Float
        get() = w1Dp * mmPerDp / 1000f / 2f

    val w2LeftM: Float
        get() = w1RightM + gapM

    val w2RightM: Float
        get() = w2LeftM + w2Dp * mmPerDp / 1000f

    /** Horizontal angle of x at the windows' depth, degrees right of straight ahead. */
    fun angleDeg(x: Float): Float = Math.toDegrees(atan2(x.toDouble(), distanceM.toDouble())).toFloat()

    /** From the eye, does any part of W2 cover W1? Same depth, so it is just their edges. */
    val overlaps: Boolean
        get() = w2LeftM <= w1RightM

    /** The viewfinder's horizontal angle (552 dp wide). */
    fun viewfinderDeg(vfDp: Int = 552): Float = 2f * angleDeg(vfDp * mmPerDp / 1000f / 2f)
}
