package io.embodit.filtercam.platform

import android.content.Context
import android.os.SharedMemory
import android.os.SystemClock
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.PipelineTensorPlaceholder
import com.pico.spatial.ml.securemr.SceneGraphProperty
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.domain.CameraMasks
import java.nio.ByteOrder
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
 * The viewfinder (design P2 / E0, ported from Low Vision Walk's LensController): a SpatialML session in secure mode whose
 * VOLUMETRIC container (Shared Space only) shows a 0.432 x 0.324 m panel; the panel's base colour texture is a dynamic
 * texture the pipeline writes.
 *
 *  - Photo sources (sample / my photo): the app renders the filter itself ([io.embodit.filtercam.domain.FilterEngine]) and
 *    uploads the 640 x 480 RGB result; the pipeline only converts it to RGBA. So the window shows exactly the picture
 *    that is saved (design M4). Resubmitted every [PHOTO_MS], each run chained after the last, the first after the init
 *    task (a write before the binding is never shown, Low Vision v0.1.3).
 *  - Camera (headset only): rectifiedVSTAccess → `out = keep * src + greyW * grey + add` with [CameraMasks] → RGBA,
 *    every [FRAME_MS] (15 Hz). Never built without a camera (the emulator), design emulatorPolicy.
 *  - Shutter on the camera ([still]): one run copies the raw frame into a global tensor that is read back
 *    (`com.pico.spatial.ml.readback`, needs CAMERA + SPATIAL_DATA); the app filters and saves it.
 *
 * The session's destroy runs on its own thread (it never returned on the emulator once a camera frame had been asked
 * for, and now and then after quick open / close cycles). A new session waits up to [DESTROY_WAIT_MS] for that destroy
 * and is created off the main thread with [CREATE_TIMEOUT_MS]: the OpenMR `initializeSession` binder call blocks while
 * a destroy is stuck, which froze the main thread into an ANR in v0.1.1's 10x open / close run. A run that fails [LOST_AFTER] times in a row, or no completed run for [LOST_MS], means the window was closed by
 * the system (design S25 / N22): the store closes the lens as if its button had been pressed.
 */
class LensController(private val appContext: Context, private val hasCamera: Boolean) : FcStore.LensHost {
    private val scope: CoroutineScope = MainScope()
    private var job: Job? = null
    @Volatile private var destroying: Thread? = null
    private var loop: Job? = null

    private var instance: SpatialMLInstance? = null
    private var session: SpatialMLSession? = null
    private var scene: GlobalTensor? = null
    private var tex: GlobalTensor? = null
    private var photo: GlobalTensor? = null
    private var masks: Array<GlobalTensor>? = null
    private var raw: GlobalTensor? = null
    private var lastTask: Pipeline.RunTask? = null
    private var initTask: Pipeline.RunTask? = null
    private var camera = false
    private var paused = false
    private var pendingPhoto: ByteArray? = null
    private var pendingMasks: CameraMasks? = null

    /** Debug (`lens --es fail init|timeout|lost`, design S21 / S25): the next open fails this way. */
    @Volatile var failNext: String? = null

    override val isOpen: Boolean
        get() = session != null

    override fun open() {
        close()
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
                val prepared = runCatching { prepare(s) }
                if (prepared.exceptionOrNull() is CancellationException) return@launch
                prepared.onFailure { FcLog.e("LENS prepare failed: $it", it) }
                if (prepared.isFailure) {
                    FcStore.lensFailed("SpatialML: scene or tensors could not be created")
                    close()
                    return@launch
                }
                FcLog.i("LENS ready in ${SystemClock.elapsedRealtime() - started} ms")
                FcStore.lensReady()
                pendingPhoto?.let { showPhoto(it) }
                pendingMasks?.let { showCamera(it) }
            }
    }

    override fun close() {
        loop?.cancel()
        loop = null
        job?.cancel()
        job = null
        lastTask = null
        initTask = null
        val s = session
        session = null
        scene = null
        tex = null
        photo = null
        masks = null
        raw = null
        paused = false
        if (s != null) {
            FcLog.i("LENS closed")
            destroying = Thread({
                val t0 = SystemClock.elapsedRealtime()
                runCatching { s.destructor.destroy() }.onFailure { FcLog.w("LENS session destroy: $it") }
                FcLog.i("LENS session destroyed in ${SystemClock.elapsedRealtime() - t0} ms")
            }, "lens-destroy").apply { isDaemon = true; start() }
        }
    }

    /** Debug `lens --es fail lost`: behave as if the system closed the window. */
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

    /** A new filtered photo frame (640 x 480 RGB); starts the photo loop if the camera ran before. */
    override fun showPhoto(rgb: ByteArray) {
        pendingPhoto = rgb
        pendingMasks = null
        val s = session ?: return
        val p = photo ?: return
        val ok = runCatching { p.tensorResource = bytesMem("fc_photo", rgb) }
        if (ok.isFailure) return lost(s, "photo write: ${ok.exceptionOrNull()}")
        if (camera || loop?.isActive != true) startLoop(s, camera = false)
    }

    /** Camera source with these masks (headset only). */
    override fun showCamera(m: CameraMasks) {
        pendingMasks = m
        pendingPhoto = null
        val s = session ?: return
        val g = masks ?: return
        val ok =
            runCatching {
                g[0].tensorResource = floats("fc_keep", m.keep)
                g[1].tensorResource = floats("fc_grey", m.greyW)
                g[2].tensorResource = floats("fc_add", m.add)
            }
        if (ok.isFailure) return lost(s, "mask write: ${ok.exceptionOrNull()}")
        if (!hasCamera) {
            FcLog.i("LENS camera source without a camera: no camera pipeline (design emulatorPolicy)")
            return
        }
        if (!camera || loop?.isActive != true) startLoop(s, camera = true)
    }

    override fun pause(on: Boolean) {
        paused = on
    }

    /** Reads one raw camera frame back (640 x 480 RGB), or null. Needs CAMERA + SPATIAL_DATA (design N16). */
    override suspend fun still(): ByteArray? {
        val s = session ?: return null
        if (!hasCamera) return null
        val r = raw ?: return null
        return runCatching {
            val p = s.newPipeline()
            val map = HashMap<PipelineTensorPlaceholder, GlobalTensor>()
            p.run {
                val src = newLocalTensor(rgb(H, W)).also { rectifiedVSTAccess(it, null, null, null) }
                val ph = newPlaceholderLike(r).also { map[it] = r }
                copy(src, ph)
            }
            lastTask = p.submit(map, null, lastTask)
            delay(STILL_WAIT_MS)
            val content = r.readbackContentSuspend()
            val buf = content.buffer
            buf.rewind()
            val bytes = ByteArray(minOf(buf.remaining(), W * H * 3))
            buf.get(bytes)
            content.close()
            FcLog.i("READBACK raw ${bytes.size} bytes (expected ${W * H * 3})")
            if (bytes.size < W * H * 3) null else bytes
        }.getOrElse {
            FcLog.w("READBACK failed: $it")
            null
        }
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
        // a container unit is 0.4 mm (Low Vision v0.1.3): 1100 x 825 = 0.44 x 0.33 m holds the 0.432 x 0.324 m panel
        // (QA #1 q1-3: at 0.6 m the right half sat outside the default view)
        val info = SpatialMLSession.InitInfo(W, H, CONTAINER_W, CONTAINER_H, CONTAINER_D, SpatialMLSession.ContainerType.VOLUMETRIC)
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

    private suspend fun prepare(s: SpatialMLSession) {
        val sc = s.newSceneFromGLTFSuspend("lens/lens_panel.glb")
        scene = sc
        val t = s.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8A8_U_DYNAMIC, intArrayOf(H, W)))
        tex = t
        photo = s.newGlobalTensor(rgb(H, W))
        masks = Array(3) { s.newGlobalTensor(maskInfo()) }
        raw = s.newGlobalTensor(rgb(H, W))
        s.newPipeline().run {
            val scenePh = newPlaceholderLike(sc)
            val texPh = newPlaceholderLike(t)
            updateSceneGraphProperty(scenePh, "/", SceneGraphProperty.PBRMaterials[0].BaseColorTexture, texPh)
            switchSceneVisibility(scenePh, newLocalTensor(1))
            initTask = submit(mapOf(scenePh to sc, texPh to t), null, null)
        }
        lastTask = initTask
    }

    private fun startLoop(s: SpatialMLSession, camera: Boolean) {
        loop?.cancel()
        this.camera = camera
        loop =
            scope.launch {
                val bound = runCatching { if (camera) cameraPipeline(s) else photoPipeline(s) }.getOrElse {
                    FcLog.e("LENS pipeline: $it", it)
                    lost(s, "pipeline: $it")
                    return@launch
                }
                var n = 0
                var errors = 0
                var frames = 0
                var framesAt = SystemClock.elapsedRealtime()
                var okAt = SystemClock.elapsedRealtime()
                val period = if (camera) FRAME_MS else PHOTO_MS
                while (isActive && session === s) {
                    val t0 = SystemClock.elapsedRealtime()
                    if (!paused) {
                        val r = runCatching { bound.submit(lastTask) }
                        if (r.isSuccess) {
                            lastTask = r.getOrNull()
                            errors = 0
                            okAt = t0
                            frames++
                            if (++n == 2) {
                                FcLog.i("LENS picture up ${if (camera) "camera" else "photo"}: 2 runs")
                                FcStore.lensLive()
                            }
                        } else if (++errors >= LOST_AFTER || t0 - okAt > LOST_MS) {
                            lost(s, "runs failed: ${r.exceptionOrNull()}")
                            break
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - framesAt > 5_000) {
                        FcLog.i("LENS ${if (camera) "camera" else "photo"} ${"%.1f".format(frames * 1000f / (now - framesAt))} Hz")
                        frames = 0
                        framesAt = now
                    }
                    delay((period - (now - t0)).coerceAtLeast(5))
                }
            }
    }

    private class Bound(val pipeline: Pipeline, val map: Map<PipelineTensorPlaceholder, GlobalTensor>) {
        fun submit(after: Pipeline.RunTask?): Pipeline.RunTask = pipeline.submit(map, null, after)
    }

    private fun photoPipeline(s: SpatialMLSession): Bound {
        val p = s.newPipeline()
        val map = HashMap<PipelineTensorPlaceholder, GlobalTensor>()
        fun ph(gt: GlobalTensor) = p.newPlaceholderLike(gt).also { map[it] = gt }
        p.run { convertColor(Pipeline.ColorConversion.RGB_TO_RGBA, ph(photo!!), ph(tex!!)) }
        return Bound(p, map)
    }

    private fun cameraPipeline(s: SpatialMLSession): Bound {
        val g = masks!!
        val p = s.newPipeline()
        val map = HashMap<PipelineTensorPlaceholder, GlobalTensor>()
        fun ph(gt: GlobalTensor) = p.newPlaceholderLike(gt).also { map[it] = gt }
        p.run {
            val src = newLocalTensor(rgb(H, W)).also { rectifiedVSTAccess(it, null, null, null) }
            val grey1 = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(H, W), 1, false))
            convertColor(Pipeline.ColorConversion.RGB_TO_GRAY, src, grey1)
            val grey3 = newLocalTensor(rgb(H, W)).also { convertColor(Pipeline.ColorConversion.GRAY_TO_RGB, grey1, it) }
            fun flat() = newLocalTensor(maskInfo())
            val f = flat().also { copy(src, it) }
            val gr = flat().also { copy(grey3, it) }
            val a = flat().also { elementwiseMultiply(f, ph(g[0]), it) }
            val b = flat().also { elementwiseMultiply(gr, ph(g[1]), it) }
            val out = flat().also { arithmetic("{0} + {1} + {2}", arrayOf(a, b, ph(g[2])), it) }
            val outU8 = newLocalTensor(rgb(H, W)).also { copy(out, it) }
            convertColor(Pipeline.ColorConversion.RGB_TO_RGBA, outU8, ph(tex!!))
        }
        return Bound(p, map)
    }

    companion object {
        const val W = 640
        const val H = 480
        private const val READY_TIMEOUT_MS = 8_000L
        private const val DESTROY_WAIT_MS = 3_000L
        private const val CREATE_TIMEOUT_MS = 8_000L
        private const val FRAME_MS = 66L
        private const val PHOTO_MS = 250L
        private const val STILL_WAIT_MS = 400L
        private const val CONTAINER_W = 1100
        private const val CONTAINER_H = 825
        private const val CONTAINER_D = 100
        private const val LOST_AFTER = 3
        private const val LOST_MS = 3_000L

        private fun rgb(h: Int, w: Int) = Tensor.MultiDimensionalInitInfo(Tensor.DataType.UINT8, intArrayOf(h, w), 3, false)

        private fun maskInfo() = Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(H, W * 3), 1, false)

        private fun floats(name: String, values: FloatArray): SharedMemory {
            val mem = SharedMemory.create(name, values.size * Float.SIZE_BYTES)
            val buf = mem.mapReadWrite()
            buf.order(ByteOrder.nativeOrder()).asFloatBuffer().put(values)
            SharedMemory.unmap(buf)
            return mem
        }

        private fun bytesMem(name: String, values: ByteArray): SharedMemory {
            val mem = SharedMemory.create(name, values.size)
            val buf = mem.mapReadWrite()
            buf.put(values)
            SharedMemory.unmap(buf)
            return mem
        }
    }
}
