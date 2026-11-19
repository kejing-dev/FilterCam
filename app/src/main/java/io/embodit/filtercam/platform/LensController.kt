package io.embodit.filtercam.platform

import android.content.Context
import android.os.SystemClock
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.PipelineTensorPlaceholder
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.filtercam.app.FcStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The viewfinder, v0.2.0 (design round 4, techRoute ⑤): a SpatialML session in Readback Mode (`ContainerType.DISABLED`,
 * no runtime-owned container window, Low Vision v0.1.7 at ac93cf8). Every run copies the right-eye camera frame
 * (`rectifiedVSTAccess`, 640 x 480 RGB) into a global tensor; the app reads it back and hands the raw frame to the store,
 * which runs the same [io.embodit.filtercam.domain.FilterEngine] as the saved photo and draws it inside the main window
 * W1. So the picture and the shutter are in one window (user, 2026-11-18 headset), and the frame the shutter takes is the
 * frame on screen.
 *
 * Pipelined (design N23): two raw tensors take turns; run n+1 is submitted before frame n is read back, so the copy of
 * one frame overlaps the camera / GPU work of the next. [pipelined] = false reads each run back before the next submit
 * (the Low Vision way, 6–7 Hz on the headset), kept for the spike's comparison (debug `lens --es pipe off`).
 *
 * Needs CAMERA + SPATIAL_DATA (design container.permissions); the store only opens it with both on, and never on a device
 * without a camera (the emulator, design emulatorPolicy). The destroy runs on its own thread and a new session waits up
 * to [DESTROY_WAIT_MS] for it, created off the main thread with [CREATE_TIMEOUT_MS] (v0.1.1: a stuck destroy blocks the
 * OpenMR binder and froze the main thread). [LOST_AFTER] failed runs in a row, a failed read-back, or no frame for
 * [LOST_MS] means the session is gone (design S25 / N22).
 */
class LensController(private val appContext: Context, private val hasCamera: Boolean) : FcStore.LensHost {
    private val scope: CoroutineScope = MainScope()
    private var job: Job? = null
    private var loop: Job? = null
    @Volatile private var destroying: Thread? = null

    private var instance: SpatialMLInstance? = null
    private var session: SpatialMLSession? = null
    private var raws: Array<GlobalTensor>? = null
    private var lastTask: Pipeline.RunTask? = null
    @Volatile private var paused = false
    @Volatile private var warnedStride = false

    /** Debug (`lens --es fail init|timeout|lost`, design S21 / S25): the next open fails this way. */
    @Volatile var failNext: String? = null

    /** Debug `lens --es pipe on|off`: two tensors in turn (default) or read each run before the next. */
    @Volatile var pipelined = true

    /** Read-back frames per second over the last 5 s (dump, ui-audit). */
    @Volatile var hz = 0f
        private set

    override val isOpen: Boolean
        get() = session != null || job?.isActive == true

    override fun open() {
        if (isOpen) return
        if (!hasCamera) return FcLog.i("LENS no camera on this device: no read-back session (design emulatorPolicy)")
        job =
            scope.launch {
                val started = SystemClock.elapsedRealtime()
                when (failNext.also { failNext = null }) {
                    "init" -> return@launch FcStore.lensFailed("SpatialML init failed (debug)")
                    "timeout" -> {
                        delay(READY_TIMEOUT_MS)
                        return@launch FcStore.lensFailed("SpatialML not ready after 8 s (debug)")
                    }
                }
                val s = runCatching { createSession() }.getOrElse {
                    if (it is CancellationException) return@launch
                    FcLog.e("LENS session failed: $it", it)
                    null
                }
                if (s == null) {
                    FcStore.lensFailed(if (instance?.ready == true) "createSession returned null" else "SpatialML not ready after 8 s")
                    return@launch
                }
                session = s
                val prepared = runCatching { raws = Array(2) { s.newGlobalTensor(rgb()) } }
                if (prepared.isFailure) {
                    FcLog.e("LENS tensors failed: ${prepared.exceptionOrNull()}")
                    FcStore.lensFailed("SpatialML: tensors could not be created")
                    close()
                    return@launch
                }
                FcLog.i("LENS ready in ${SystemClock.elapsedRealtime() - started} ms (read-back, container DISABLED, pipelined=$pipelined)")
                startLoop(s)
            }
    }

    override fun close() {
        loop?.cancel()
        loop = null
        job?.cancel()
        job = null
        lastTask = null
        raws = null
        paused = false
        hz = 0f
        val s = session ?: return
        session = null
        FcLog.i("LENS closed")
        destroying =
            Thread({
                val t0 = SystemClock.elapsedRealtime()
                runCatching { s.destructor.destroy() }.onFailure { FcLog.w("LENS session destroy: $it") }
                FcLog.i("LENS session destroyed in ${SystemClock.elapsedRealtime() - t0} ms")
            }, "lens-destroy").apply {
                isDaemon = true
                start()
            }
    }

    override fun pause(on: Boolean) {
        if (paused != on) FcLog.i("LENS ${if (on) "paused" else "resumed"}")
        paused = on
    }

    /** Debug `lens --es fail lost`: behave as if the system ended the session. */
    fun simulateLost() {
        val s = session ?: return
        lost(s, "debug")
    }

    private fun lost(s: SpatialMLSession, why: String) {
        if (session !== s) return
        FcLog.w("LENS session lost: $why")
        instance = null
        FcStore.lensLost(why)
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun createSession(): SpatialMLSession? {
        val inst = instance ?: SpatialMLInstance.create(appContext).also { instance = it }
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (!inst.ready) delay(100)
            true
        }
        if (ready != true) return null
        destroying?.let { t -> withContext(Dispatchers.IO) { t.join(DESTROY_WAIT_MS) } }
        // Readback Mode: no runtime-owned container, the app draws the picture (docs: secure mode and readback mode)
        val info = SpatialMLSession.InitInfo(W, H, 0, 0, 0, SpatialMLSession.ContainerType.DISABLED)
        // not a child of this job: a stuck binder call must not hold up cancel; a late session is destroyed at once
        val pending = CoroutineScope(Dispatchers.IO).async { inst.createSession(info) }
        val s =
            try {
                withTimeoutOrNull(CREATE_TIMEOUT_MS) { pending.await() }
            } catch (e: CancellationException) {
                dropLate(pending)
                throw e
            }
        if (s == null) {
            FcLog.w("LENS createSession gave nothing in $CREATE_TIMEOUT_MS ms")
            dropLate(pending)
        }
        return s
    }

    private fun dropLate(pending: Deferred<SpatialMLSession?>) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { pending.await()?.destructor?.destroy() } }
    }

    /** One pipeline per raw tensor: rectifiedVSTAccess → copy into it. */
    private class Bound(val pipeline: Pipeline, val map: Map<PipelineTensorPlaceholder, GlobalTensor>, val out: GlobalTensor) {
        fun submit(after: Pipeline.RunTask?): Pipeline.RunTask = pipeline.submit(map, null, after)
    }

    private fun bind(s: SpatialMLSession, out: GlobalTensor): Bound {
        val p = s.newPipeline()
        val map = HashMap<PipelineTensorPlaceholder, GlobalTensor>()
        p.run {
            val src = newLocalTensor(rgb()).also { rectifiedVSTAccess(it, null, null, null) }
            val ph = newPlaceholderLike(out).also { map[it] = out }
            copy(src, ph)
        }
        return Bound(p, map, out)
    }

    private fun startLoop(s: SpatialMLSession) {
        loop?.cancel()
        loop =
            scope.launch {
                val bound = runCatching { raws!!.map { bind(s, it) } }.getOrElse {
                    FcLog.e("LENS pipeline: $it", it)
                    lost(s, "pipeline: $it")
                    return@launch
                }
                var i = 0
                var pending: Bound? = null
                var errors = 0
                var frames = 0
                var total = 0
                var framesAt = SystemClock.elapsedRealtime()
                var okAt = framesAt
                while (isActive && session === s) {
                    val t0 = SystemClock.elapsedRealtime()
                    if (paused) {
                        pending = null
                        okAt = t0
                        delay(PAUSE_POLL_MS)
                        continue
                    }
                    val b = bound[i]
                    val r = runCatching { b.submit(lastTask) }
                    if (r.isFailure) {
                        if (++errors >= LOST_AFTER || t0 - okAt > LOST_MS) {
                            lost(s, "runs failed: ${r.exceptionOrNull()}")
                            break
                        }
                        delay(FRAME_MS)
                        continue
                    }
                    lastTask = r.getOrNull()
                    errors = 0
                    // pipelined: read the frame of the run before this one; else this one
                    val read = if (pipelined) pending.also { pending = b } else b
                    i = (i + 1) % bound.size
                    if (read != null) {
                        val bytes =
                            try {
                                read.out.readbackContentSuspend().use { c -> withContext(Dispatchers.Default) { packed(c.buffer) } }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lost(s, "readback: $e")
                                break
                            }
                        if (session !== s) break
                        if (bytes != null) {
                            okAt = SystemClock.elapsedRealtime()
                            frames++
                            if (++total == 2) FcLog.i("LENS picture up: 2 frames read back")
                            FcStore.onCameraFrame(bytes)
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - okAt > LOST_MS) {
                        lost(s, "no frame for ${now - okAt} ms")
                        break
                    }
                    if (now - framesAt >= HZ_WINDOW_MS) {
                        hz = frames * 1000f / (now - framesAt)
                        FcLog.i("LENS camera ${"%.1f".format(hz)} Hz read-back (pipelined=$pipelined)")
                        frames = 0
                        framesAt = now
                    }
                    delay((FRAME_MS - (now - t0)).coerceAtLeast(1))
                }
            }
    }

    /** RGB bytes, rows packed (a padded stride is taken from the buffer size); null if short. */
    private fun packed(buffer: java.nio.ByteBuffer): ByteArray? {
        val b = buffer.duplicate().apply { rewind() }
        val stride = b.remaining() / H
        if (stride < W * 3) {
            if (!warnedStride) FcLog.w("LENS readback ${b.remaining()} bytes, expected ${W * H * 3}")
            warnedStride = true
            return null
        }
        val out = ByteArray(W * H * 3)
        if (stride == W * 3) {
            b.get(out)
        } else {
            if (!warnedStride) FcLog.i("LENS readback rows padded: stride $stride for ${W * 3}")
            warnedStride = true
            for (row in 0 until H) {
                b.position(row * stride)
                b.get(out, row * W * 3, W * 3)
            }
        }
        return out
    }

    companion object {
        const val W = 640
        const val H = 480
        private const val READY_TIMEOUT_MS = 8_000L
        private const val DESTROY_WAIT_MS = 3_000L
        private const val CREATE_TIMEOUT_MS = 8_000L

        /** At most 15 Hz (design target ≥ 10 Hz pipelined). */
        private const val FRAME_MS = 66L
        private const val PAUSE_POLL_MS = 100L
        private const val HZ_WINDOW_MS = 5_000L
        private const val LOST_AFTER = 3
        private const val LOST_MS = 3_000L

        private fun rgb() = Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(H, W), 3, false)
    }
}
