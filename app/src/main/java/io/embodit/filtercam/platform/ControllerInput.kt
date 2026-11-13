package io.embodit.filtercam.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.filtercam.domain.Quat4
import io.embodit.filtercam.domain.Vec3
import kotlin.math.abs

/**
 * Controller input in room mode (SDK doc "Obtain controller input actions", ported from Low Vision Walk): the
 * thumbstick X and the A / X (next filter) and B / Y (previous filter) buttons, design T12. The listener runs on the
 * data source thread and only stores values; the frame system reads them.
 */
class ControllerInput {
    data class Ray(val origin: Vec3, val dir: Vec3)

    private var provider: ControllerTrackingProvider? = null

    @Volatile private var stickX = 0f
    @Volatile private var stickAtMs = 0L
    @Volatile private var byDown = false
    @Volatile private var byPresses = 0
    @Volatile private var axDown = false
    @Volatile private var axPresses = 0
    @Volatile var actionCount = 0L
        private set

    private val actions =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            stickX = if (abs(r) >= abs(l)) r else l
            stickAtMs = SystemClock.elapsedRealtime()
            val down = a.right.bButtonPressed || a.left.yButtonPressed
            if (down && !byDown) byPresses++
            byDown = down
            val next = a.right.aButtonPressed || a.left.xButtonPressed
            if (next && !axDown) axPresses++
            axDown = next
            actionCount++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(actions) }.onFailure { FcLog.w("TRACK controller actions: $it") }
        val result = runCatching { p.start() }
        FcLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(actions) }
            runCatching { p.stop() }
        }
        provider = null
        stickX = 0f
        byPresses = 0
        axPresses = 0
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actionCount x=${"%.2f".format(stickX)}"

    private fun supported(): Boolean = provider?.let { p -> runCatching { p.supportState }.getOrNull() == DataProvider.SupportState.SUPPORTED } ?: false

    /** Thumbstick X in [-1, 1] (right +), 0 when stale or unsupported. */
    fun stick(now: Long): Float {
        if (!supported() || now - stickAtMs > 1_000L) return 0f
        return stickX.coerceIn(-1f, 1f)
    }

    /** B / Y presses since the last call (previous filter). */
    fun takePrevPresses(): Int {
        val n = byPresses
        byPresses = 0
        return n
    }

    /** A / X presses since the last call (next filter). */
    fun takeNextPresses(): Int {
        val n = axPresses
        axPresses = 0
        return n
    }

    /** The aiming ray of the right controller (left when only it is there), Stage space. */
    fun ray(): Ray? {
        if (!supported()) return null
        val data = runCatching { provider?.latestData }.getOrNull() ?: return null
        val pose = data.right ?: data.left ?: return null
        val origin = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val dir = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w).rotate(Vec3.AHEAD)
        if (!origin.isFinite() || origin.length < 1e-4f || !dir.isFinite()) return null
        return Ray(origin, dir.normalized())
    }
}
