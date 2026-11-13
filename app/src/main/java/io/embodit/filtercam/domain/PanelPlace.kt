package io.embodit.filtercam.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the room panel P5 goes (design P5 / T11 / T13, profile D9 / D29): [distM] ahead along the head's level heading,
 * [dropDeg] below the level eye line (+ down), [leftDeg] to the left. Upright, turned about the vertical only.
 */
data class PanelSpot(val distM: Float, val dropDeg: Float, val leftDeg: Float)

/** A panel's place in Stage metres and its turn about the vertical (+Z of the panel towards the viewer). No pitch, no roll. */
data class UprightPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

object PanelPlace {
    /** Design P5: 视线下方 15°、左偏 20°、距 0.9 m. */
    val ROOM_PANEL = PanelSpot(0.9f, 15f, 20f)

    /** Head still for this long before the panel is placed (design D29: 等头稳定 0.5 s 再取样). */
    const val SETTLE_MS = 500L

    /** Max head turn (degrees) within the settle window that still counts as still. */
    const val STILL_DEG = 3f

    /** Yaw of a level direction, 0 = looking along -Z (Stage ahead), + turning left. */
    fun yawOf(dir: Vec3): Float = atan2(-dir.x, -dir.z)

    /** Level heading of the head (radians, [yawOf] convention). Looking straight down / up, the head's up decides. */
    fun heading(head: HeadPose): Float {
        val f = head.forward
        var flat = Vec3(f.x, 0f, f.z)
        if (flat.length < 0.2f) {
            val u = head.up
            flat = if (f.y < 0f) Vec3(u.x, 0f, u.z) else Vec3(-u.x, 0f, -u.z)
        }
        return if (flat.length < 1e-3f) 0f else yawOf(flat.normalized())
    }

    /** [spot] in front of [head] along its level heading, upright and facing the eye (profile D9 / D10). */
    fun placeUpright(head: HeadPose, spot: PanelSpot, heading: Float = heading(head)): UprightPose {
        val drop = Math.toRadians(spot.dropDeg.toDouble()).toFloat()
        val yaw = heading + Math.toRadians(spot.leftDeg.toDouble()).toFloat()
        // yaw 0 = -Z; + = turned left (towards -X)
        val dir = Vec3(-sin(yaw) * cos(drop), -sin(drop), -cos(yaw) * cos(drop))
        val at = head.position + dir * spot.distM
        return UprightPose(at, faceYaw(at, head.position, yaw))
    }

    /** Yaw (about +Y, +Z of the panel towards [eye]) for a panel at [at]; straight above / below keeps [fallback]. */
    fun faceYaw(at: Vec3, eye: Vec3, fallback: Float): Float {
        val dx = eye.x - at.x
        val dz = eye.z - at.z
        return if (dx * dx + dz * dz < 1e-6f) fallback else atan2(dx, dz)
    }

    /** Angle in degrees between two yaws, 0..180. */
    fun yawDiffDeg(a: Float, b: Float): Float {
        var d = Math.toDegrees((a - b).toDouble()) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return kotlin.math.abs(d).toFloat()
    }
}

/**
 * D29: 「全屋试试」 / 「放到眼前」 sample the head only once it has been still for [PanelPlace.SETTLE_MS] (the press
 * happens while looking at the button). Feed every frame; [placeNow] says when.
 */
class SettleGate(private val settleMs: Long = PanelPlace.SETTLE_MS, private val stillDeg: Float = PanelPlace.STILL_DEG, private val maxWaitMs: Long = 3_000L) {
    private var askedMs = -1L
    private var stillSinceMs = -1L
    private var lastHeading = 0f

    val waiting: Boolean
        get() = askedMs >= 0

    fun ask(now: Long) {
        askedMs = now
        stillSinceMs = -1L
    }

    /** True once (then the gate is idle again) when the head has been still for long enough, or after [maxWaitMs]. */
    fun placeNow(now: Long, head: HeadPose?): Boolean {
        if (askedMs < 0) return false
        if (head == null) return false
        val h = PanelPlace.heading(head)
        if (stillSinceMs < 0 || PanelPlace.yawDiffDeg(h, lastHeading) > stillDeg) {
            stillSinceMs = now
            lastHeading = h
        }
        if (now - stillSinceMs >= settleMs || now - askedMs >= maxWaitMs) {
            askedMs = -1L
            return true
        }
        return false
    }
}
