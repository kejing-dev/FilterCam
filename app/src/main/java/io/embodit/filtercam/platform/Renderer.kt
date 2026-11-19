package io.embodit.filtercam.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import io.embodit.filtercam.domain.CubeParser
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.FilterEngine
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.domain.Img
import io.embodit.filtercam.domain.Look
import io.embodit.filtercam.domain.Lut3D
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A live camera frame as the viewfinder shows it: the raw read-back frame and that frame through the filter. */
class LiveFrame(val raw: Img, val shown: Bitmap, val seq: Long)

/**
 * Runs [FilterEngine] off the main thread for the viewfinder (photo sources and, v0.2.0, the read-back camera frames),
 * the filter cards and the shutter, and owns the LUTs (built-in .cube assets and imported ones in filesDir/luts, design
 * persistence.luts) and the sample photos. One worker for photo previews (only the newest request runs), three for camera
 * frames (frame n+1 renders while n is still running; an older frame never replaces a newer one), one for thumbnails.
 */
class Renderer(private val context: Context) {
    private val previewExec = Executors.newSingleThreadExecutor { Thread(it, "fc-preview").apply { isDaemon = true } }
    private val thumbExec = Executors.newSingleThreadExecutor { Thread(it, "fc-thumbs").apply { isDaemon = true } }
    private val liveExec = Executors.newFixedThreadPool(LIVE_WORKERS) { Thread(it, "fc-live").apply { isDaemon = true } }
    private val liveBusy = AtomicInteger()
    @Volatile private var liveShownSeq = 0L
    private var liveSeqNext = 0L
    private var liveCount = 0
    private var liveMsSum = 0L
    private var liveAt = SystemClock.elapsedRealtime()

    private val _live = MutableStateFlow<LiveFrame?>(null)
    val live: StateFlow<LiveFrame?> = _live.asStateFlow()

    /** Camera frames shown per second and the mean render time over the last 5 s (dump, ui-audit: design N23). */
    @Volatile var liveHz = 0f
        private set
    @Volatile var liveRenderMs = 0f
        private set
    @Volatile var liveDropped = 0
        private set
    private val previewGen = AtomicInteger()
    private val luts = ConcurrentHashMap<String, Lut3D>()
    private val samples = ConcurrentHashMap<String, Img>()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<String, Bitmap>> = _thumbs.asStateFlow()

    val lutDir: File
        get() = File(context.filesDir, "luts").apply { mkdirs() }

    /** Names of the imported LUTs (file names without .cube), oldest first. */
    fun importedLuts(): List<String> = (lutDir.listFiles { f -> f.name.endsWith(".cube") } ?: emptyArray()).sortedBy { it.lastModified() }.map { it.name.removeSuffix(".cube") }

    /** The LUT of [def] (built-in asset or imported file), loaded once. */
    @Synchronized
    fun lutOf(def: FilterDef): Lut3D? {
        val key = def.params.lut ?: return null
        luts[key]?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val text =
            runCatching {
                if (key.startsWith(FilterDef.LUT_PREFIX)) File(lutDir, key.removePrefix(FilterDef.LUT_PREFIX) + ".cube").readText()
                else context.assets.open("luts/$key.cube").bufferedReader().use { it.readText() }
            }.getOrElse {
                FcLog.w("LUT $key not found: $it")
                return null
            }
        val lut = runCatching { CubeParser.parse(text) }.getOrElse {
            FcLog.w("LUT $key unreadable: $it")
            return null
        }
        luts[key] = lut
        FcLog.i("LUT $key loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        return lut
    }

    /** Parses and keeps an imported .cube ([name] without extension); throws [io.embodit.filtercam.domain.CubeError]. */
    fun importLut(name: String, text: String) {
        val lut = CubeParser.parse(text)
        File(lutDir, "$name.cube").writeText(text)
        luts[FilterDef.LUT_PREFIX + name] = lut
    }

    fun deleteLut(name: String) {
        File(lutDir, "$name.cube").delete()
        luts.remove(FilterDef.LUT_PREFIX + name)
        _thumbs.value = _thumbs.value - (FilterDef.LUT_PREFIX + name)
    }

    /** A sample photo at [width] (4:3), decoded from assets once at 1024. */
    fun sample(name: String, width: Int = 1024): Img {
        val full =
            samples.getOrPut(name) {
                val bmp = context.assets.open("samples/$name.jpg").use { BitmapFactory.decodeStream(it) }
                Bitmaps.toImg(bmp).also { bmp.recycle() }
            }
        return if (width == full.w) full else full.resized(width, width * full.h / full.w)
    }

    /**
     * Renders [src] with [look] at the preview size and publishes the bitmap; [onFrame] gets the filtered picture (for
     * the viewfinder's photo tensor). Only the newest request runs.
     */
    fun requestPreview(src: () -> Img?, look: () -> Look?, onFrame: (Img) -> Unit = {}) {
        val gen = previewGen.incrementAndGet()
        previewExec.execute {
            if (gen != previewGen.get()) return@execute
            val s = src()
            val l = look()
            if (s == null || l == null) {
                _preview.value = null
                return@execute
            }
            val t0 = SystemClock.elapsedRealtime()
            val img = runCatching { FilterEngine.render(s, l) }.getOrElse {
                FcLog.e("RENDER preview failed: $it", it)
                return@execute
            }
            if (gen != previewGen.get()) return@execute
            _preview.value = Bitmaps.toBitmap(img)
            onFrame(img)
            val ms = SystemClock.elapsedRealtime() - t0
            if (ms > 150) FcLog.i("RENDER preview ${l.filter.id} ${s.w}x${s.h} $ms ms")
        }
    }

    /**
     * A read-back camera frame (640 x 480 RGB) through [look], for the viewfinder. Dropped while all workers are busy
     * (the next frame is a few tens of ms away); a frame finishing after a newer one is not shown.
     */
    fun renderLive(rgb: ByteArray, w: Int, h: Int, look: Look) {
        if (liveBusy.get() >= LIVE_WORKERS) {
            liveDropped++
            return
        }
        liveBusy.incrementAndGet()
        val seq = synchronized(this) { ++liveSeqNext }
        liveExec.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val raw = Bitmaps.fromRgb(rgb, w, h)
                val img = runCatching { FilterEngine.render(raw, look) }.getOrElse {
                    FcLog.e("RENDER live failed: $it", it)
                    return@execute
                }
                val bmp = Bitmaps.toBitmap(img)
                val ms = SystemClock.elapsedRealtime() - t0
                synchronized(this) {
                    if (seq <= liveShownSeq) return@execute
                    liveShownSeq = seq
                    _live.value = LiveFrame(raw, bmp, seq)
                    liveCount++
                    liveMsSum += ms
                    val now = SystemClock.elapsedRealtime()
                    if (now - liveAt >= 5_000) {
                        liveHz = liveCount * 1000f / (now - liveAt)
                        liveRenderMs = liveMsSum.toFloat() / liveCount
                        FcLog.i("VIEWFINDER ${"%.1f".format(liveHz)} Hz shown, render ${"%.0f".format(liveRenderMs)} ms, dropped $liveDropped")
                        liveCount = 0
                        liveMsSum = 0
                        liveDropped = 0
                        liveAt = now
                    }
                }
            } finally {
                liveBusy.decrementAndGet()
            }
        }
    }

    /** The camera stopped: no stale frame on the next start. */
    fun clearLive() {
        synchronized(this) {
            _live.value = null
            liveHz = 0f
            liveCount = 0
            liveMsSum = 0
            liveAt = SystemClock.elapsedRealtime()
        }
    }

    /** Card thumbnails (design W2 cards): the filter's own sample photo through it, 320 x 240. */
    fun buildThumbs(defs: List<FilterDef>, stampText: String?) {
        thumbExec.execute {
            val t0 = SystemClock.elapsedRealtime()
            val out = HashMap(_thumbs.value)
            for (def in defs) {
                val src = sample(def.cardSample, THUMB_W)
                val look = Look(def, lutOf(def), 1f, THUMB_SEED, if (def.stampByDefault) stampText else null)
                val img = runCatching { FilterEngine.render(src, look) }.getOrNull() ?: continue
                out[def.id] = Bitmaps.toBitmap(img)
                _thumbs.value = HashMap(out)
            }
            FcLog.i("THUMBS ${defs.size} in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /** Full-size render on the caller's thread (shutter: 1024 x 768, design T5). */
    fun renderFull(src: Img, look: Look): Img = FilterEngine.render(src, look)

    fun allFilters(): List<FilterDef> = Filters.builtIn + importedLuts().map { Filters.lutFilter(it) }

    companion object {
        const val PREVIEW_W = 640
        const val THUMB_W = 320
        const val THUMB_SEED = 7
        private const val LIVE_WORKERS = 3
    }
}
