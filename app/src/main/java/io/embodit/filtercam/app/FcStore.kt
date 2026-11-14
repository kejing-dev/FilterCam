package io.embodit.filtercam.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.filtercam.domain.CameraMasks
import io.embodit.filtercam.domain.CubeError
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.domain.Img
import io.embodit.filtercam.domain.LevelGate
import io.embodit.filtercam.domain.Look
import io.embodit.filtercam.domain.RoomMode
import io.embodit.filtercam.domain.Stamp
import io.embodit.filtercam.domain.StampFormat
import io.embodit.filtercam.platform.Bitmaps
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.HelperActivity
import io.embodit.filtercam.platform.LensController
import io.embodit.filtercam.platform.PhotoRepo
import io.embodit.filtercam.platform.Prefs
import io.embodit.filtercam.platform.Renderer
import io.embodit.filtercam.platform.SoundBank
import java.util.Calendar
import java.util.Date
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one state holder of the process: the main window, the lens spot, the shot card, the room Stage and the debug
 * receiver all call these functions on the main thread (debug commands take the same paths as taps, profile D29).
 * Per-frame 3D work never goes through here; the room scene reports discrete events.
 */
object FcStore {
    /** The viewfinder (SpatialML), see [LensController]. */
    interface LensHost {
        val isOpen: Boolean

        fun open()

        fun close()

        fun showPhoto(rgb: ByteArray)

        fun showCamera(m: CameraMasks)

        fun pause(on: Boolean)

        suspend fun still(): ByteArray?
    }

    /** Opens / closes a system window by id (lent by the main window, which owns the navigator). */
    interface WindowHost {
        fun openSpot(gen: Int)

        fun closeSpot()

        fun openStage()

        fun closeStage()

        /** 「叫回面前」: the main window again where the system opens a new one (in front of the head), this one closed. */
        fun recallFront()
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val _state = MutableStateFlow(FcState())
    val state: StateFlow<FcState> = _state.asStateFlow()

    var lensHost: LensHost? = null
    var windowHost: WindowHost? = null

    /** Closes the room Stage from inside it (the main window is closed while the room is on). */
    var stageCloser: (() -> Unit)? = null

    /** The main window closed itself for room mode; the Stage opens it again when it ends. */
    @Volatile var hiddenHome: Pair<String, String?>? = null

    /** Lifecycle of the main window as it reports it (ON_CREATE … ON_DESTROY, "never"). */
    val homeLife = MutableStateFlow("never")

    lateinit var renderer: Renderer
        private set
    lateinit var photos: PhotoRepo
        private set
    private var sounds: SoundBank? = null
    private var prefs: Prefs? = null
    private var appContext: Context? = null

    private val random = Random(SystemClock.elapsedRealtime())

    /** Seed of the live pictures (fixed while viewing, design M5); `seed --ei n` fixes the shot seed too. */
    private var liveSeed = 3
    private var fixedSeed: Int? = null

    /** The imported photo at 1024 x 768 (design S09) and the full-size picture a redo starts from. */
    private var importImg: Img? = null

    /** Debug `hold --es state …` (design qaHooks, D36): a transition under 3 s stays until `release`. */
    @Volatile var held: String? = null
        private set
    private val heldRelease = ArrayList<() -> Unit>()

    /** Debug `readback --es mode ok|denied|fail|sessionLevel` (design qaHooks): a fake camera shutter on the emulator. */
    @Volatile var fakeReadback: String? = null

    private var noticeSerial = 0
    private var cardSerial = 0
    private var lensWaitingForSpot = false

    @Volatile var spotGen = 0
        private set

    private val s: FcState
        get() = _state.value

    fun now() = SystemClock.elapsedRealtime()

    // =========================================================================================
    // setup
    // =========================================================================================

    fun init(context: Context, prefs: Prefs, isEmulator: Boolean) {
        appContext = context.applicationContext
        this.prefs = prefs
        renderer = Renderer(context.applicationContext)
        photos = PhotoRepo(context.applicationContext)
        sounds = SoundBank(context.applicationContext)
        val luts = renderer.importedLuts()
        var start = prefs.restore(FcState(isEmulator = isEmulator, luts = luts))
        if (Filters.of(start.filterId, luts)?.available != true) start = start.copy(filterId = Filters.F01.id)
        val granted = HelperActivity.granted(context)
        start = start.copy(permissionGranted = granted, permissionDenied = start.permissionDenied && !granted, permissionBlocked = start.permissionBlocked && !granted, overlay = if (prefs.guideSeen()) null else Overlay.GUIDE)
        _state.value = start
        FcLog.i("STATE ${start.stateId} init filter=${start.filterId} lang=${start.lang} emulator=$isEmulator luts=${luts.size}")
        refreshPreview()
        renderer.buildThumbs(renderer.allFilters(), stampTextFor(StampFormat.SHORT))
    }

    private fun set(next: FcState, why: String) {
        val prev = s
        _state.value = next
        if (prev.stateId != next.stateId) FcLog.i("STATE ${next.stateId} ($why)")
        else FcLog.i("EVENT $why")
        prefs?.save(next)
        val visual =
            prev.previewFilter != next.previewFilter || prev.strength != next.strength || prev.stampOn != next.stampOn || prev.stampFormat != next.stampFormat ||
                prev.compare != next.compare || prev.source != next.source || prev.sample != next.sample || prev.importName != next.importName ||
                prev.lens.open != next.lens.open || prev.luts != next.luts
        if (visual) refreshPreview()
    }

    fun t(id: Int, vararg args: Any): String {
        val ctx = appContext ?: return ""
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(if (s.lang == Lang.EN) java.util.Locale.ENGLISH else java.util.Locale.SIMPLIFIED_CHINESE)
        return ctx.createConfigurationContext(cfg).resources.getString(id, *args)
    }

    // =========================================================================================
    // looks and the preview
    // =========================================================================================

    fun stampTextFor(format: StampFormat, date: Date = Date()): String? {
        val c = Calendar.getInstance().apply { time = date }
        return Stamp.text(format, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun def(id: String = s.filterId): FilterDef = Filters.of(id, s.luts) ?: Filters.F01

    fun lookOf(st: FcState, filterId: String, seed: Int): Look {
        val d = Filters.of(filterId, st.luts) ?: Filters.F01
        val stamp = if (st.stampOn) stampTextFor(st.stampFormat) else null
        return Look(d, renderer.lutOf(d), st.strength / 100f, seed, stamp, compare = st.compare)
    }

    /** The picture of the current source at the preview size (4:3), or null for the camera. */
    private fun sourceImg(st: FcState, width: Int): Img? =
        when (st.source) {
            Source.SAMPLE -> renderer.sample(st.sample, width)
            Source.IMPORT -> importImg?.let { if (it.w == width) it else it.resized(width, width * it.h / it.w) }
            Source.CAMERA -> null
        }

    private fun refreshPreview() {
        val st = s
        val hovering = st.hoverId != null
        val lensOpen = st.lens.open
        // the camera: the preview box shows the chosen filter on the sample (design H3), the lens gets the masks
        val previewSrcSample = hovering || st.source == Source.CAMERA
        renderer.requestPreview(
            src = { if (previewSrcSample) renderer.sample(st.sample, Renderer.PREVIEW_W) else sourceImg(st, Renderer.PREVIEW_W) },
            look = { lookOf(st, st.previewFilter, liveSeed) },
        ) { img ->
            if (!hovering && lensOpen && st.source != Source.CAMERA) {
                val rgb = Bitmaps.rgbBytes(img, LensController.W, LensController.H)
                main.post { lensHost?.showPhoto(rgb) }
            }
        }
        if (lensOpen && st.source == Source.CAMERA && !hovering) {
            if (st.isEmulator) showNoCameraInLens()
            else scope.launch {
                val m = withContext(Dispatchers.Default) { CameraMasks.of(lookOf(st, st.filterId, liveSeed), LensController.W, LensController.H) }
                lensHost?.showCamera(m)
            }
        }
    }

    /** S22: the viewfinder shows the "no camera" line as a still picture (design: 应用上传的占位 640×480). */
    private fun showNoCameraInLens() {
        val ctx = appContext ?: return
        val text = t(io.embodit.filtercam.R.string.no_camera)
        scope.launch {
            val rgb =
                withContext(Dispatchers.Default) {
                    val bmp = android.graphics.Bitmap.createBitmap(LensController.W, LensController.H, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(bmp)
                    c.drawColor(0xFF101216.toInt())
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F4F7.toInt(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER }
                    val words = text.chunked(22)
                    words.forEachIndexed { i, line -> c.drawText(line, LensController.W / 2f, LensController.H / 2f + (i - words.size / 2f) * 36f + 10f, paint) }
                    Bitmaps.rgbBytes(Bitmaps.toImg(bmp), LensController.W, LensController.H)
                }
            lensHost?.showPhoto(rgb)
            FcLog.i("LENS no camera placeholder (${ctx.packageName})")
        }
    }

    // =========================================================================================
    // camera page controls (design T1 – T7)
    // =========================================================================================

    fun pageSelected(page: String) = set(s.copy(page = page, quitAsked = false, hoverId = null), "page $page")

    /** 「更多」 / 「收起」 on the camera page (design round 3, S29). */
    fun moreToggled(open: Boolean = !s.moreOpen) = set(s.copy(moreOpen = open, hoverId = null), "more $open")

    /** ‹ › on the camera page: the v1 filters that are available plus the imported LUTs, round and round (v1.1 cards skipped). */
    fun filterStep(step: Int, why: String = "button") {
        val ids = (Filters.builtIn.filter { it.available } + s.luts.map { Filters.lutFilter(it) }).map { it.id }
        if (ids.isEmpty()) return
        val i = ids.indexOf(s.filterId).coerceAtLeast(0)
        filterChosen(ids[Math.floorMod(i + step, ids.size)], why)
    }

    fun filterChosen(id: String, why: String = "tap") {
        val d = Filters.of(id, s.luts) ?: return FcLog.w("CMD fail filter $id unknown")
        if (!d.available) {
            set(s.copy(shakeId = id, shakeSerial = s.shakeSerial + 1, notice = notice(NoticeKind.V11, id)), "v1.1 card $id ($why)")
            return
        }
        play(SoundBank.Cue.TICK)
        set(s.copy(filterId = id, hoverId = null, stampOn = d.stampByDefault, compare = false, notice = null), "filter $id ($why)")
    }

    fun hover(id: String, on: Boolean) {
        if (on) {
            if (s.hoverId == id) return
            if (Filters.of(id, s.luts)?.available != true) return
            set(s.copy(hoverId = id), "hover $id")
        } else if (s.hoverId == id) set(s.copy(hoverId = null), "hover end $id")
    }

    fun strengthChanged(n: Int) {
        val v = n.coerceIn(0, 100)
        if (v != s.strength) set(s.copy(strength = v), "strength $v")
    }

    fun stampToggled(on: Boolean = !s.stampOn) = set(s.copy(stampOn = on), "stamp $on")

    fun compareToggled(on: Boolean = !s.compare) = set(s.copy(compare = on), "compare $on")

    fun timerToggled(on: Boolean = !s.timerOn) = set(s.copy(timerOn = on), "timer $on")

    fun muteToggled(on: Boolean = !s.muted) = set(s.copy(muted = on), "mute $on")

    fun volumeChanged(step: Int) = set(s.copy(volume = step.coerceIn(0, 2)), "volume $step")

    fun langSelected(l: Lang) = set(s.copy(lang = l), "lang $l")

    fun stampFormatSelected(f: StampFormat) {
        set(s.copy(stampFormat = f, stampOn = if (f == StampFormat.OFF) false else s.stampOn), "stamp format ${f.id}")
        renderer.buildThumbs(renderer.allFilters(), stampTextFor(if (f == StampFormat.OFF) StampFormat.SHORT else f))
    }

    fun saveRawToggled(on: Boolean = !s.saveRaw) = set(s.copy(saveRaw = on), "save raw $on")

    fun sampleSelected(name: String) {
        if (name !in Samples.all) return
        set(s.copy(sample = name, source = if (s.source == Source.CAMERA) s.source else Source.SAMPLE), "sample $name")
    }

    /** Design T4: 相机 opens the viewfinder first (camera only while it is open); 我的照片 goes through the picker. */
    /** [auto]: the start-up camera on the headset; it asks only if the user has never refused (no dialog on every start). */
    fun sourceSelected(src: Source, auto: Boolean = false) {
        when (src) {
            Source.SAMPLE -> set(s.copy(source = Source.SAMPLE, notice = null), "source sample")
            Source.CAMERA -> {
                set(s.copy(source = Source.CAMERA, notice = null), "source camera")
                if (!s.lens.open) openLens("camera source")
                // ask before the camera is used (2026-11-14 headset: the app never asked); the shutter's read-back needs it
                if (!cameraGranted() && !s.permissionBlocked && !(auto && s.permissionDenied)) askCamera(if (auto) "camera source (start)" else "camera source")
            }
            Source.IMPORT -> {
                if (importImg != null && s.importName != null) set(s.copy(source = Source.IMPORT, notice = null), "source import (kept)")
                pickPhoto()
            }
        }
    }

    fun pickPhoto() {
        val ctx = appContext ?: return
        FcLog.i("EVENT picker photo (S09p)")
        HelperActivity.launch(ctx, HelperActivity.MODE_PHOTO)
    }

    /** Debug `import --es fail bad` (design S27): the next import fails. */
    @Volatile var failNextImport = false

    fun photoPicked(uri: Uri?) {
        if (uri == null) {
            FcLog.i("EVENT picker cancelled")
            return
        }
        importFrom(uri, null)
    }

    /** Loads a picture as the 「我的照片」 source (picker, gallery 「用这张当素材」, shot card 「换个滤镜重出」). */
    fun importFrom(uri: Uri, name: String?) {
        scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (failNextImport) {
                            failNextImport = false
                            throw java.io.IOException("debug import fail")
                        }
                        photos.decode(uri, 2048).fitFourThree(1024) to (name ?: photos.displayName(uri))
                    }
                }
            r.onSuccess { (img, nm) ->
                importImg = img
                set(s.copy(source = Source.IMPORT, importName = nm, page = Pages.SHOOT, notice = null), "import $nm ${img.w}x${img.h}")
            }.onFailure {
                FcLog.w("IMPORT failed $uri: $it")
                set(s.copy(notice = notice(NoticeKind.IMPORT_FAILED)), "import failed")
            }
        }
    }

    /** Debug / tests: use a sample as if it had been imported (no system picker on the QA path). */
    fun importSampleForDebug(name: String) {
        importImg = renderer.sample(name, 1024)
        set(s.copy(source = Source.IMPORT, importName = "$name.jpg", page = Pages.SHOOT), "import debug $name")
    }

    private fun notice(kind: NoticeKind, arg: String? = null) = Notice(kind, arg, ++noticeSerial)

    fun noticeDismissed() = set(s.copy(notice = null), "notice dismissed")

    // =========================================================================================
    // the viewfinder (T3, S02 / S21 / S22 / S25)
    // =========================================================================================

    fun lensButton() = if (s.lens.open) closeLens("button", toSample = true) else openLens("button")

    fun openLens(why: String) {
        if (s.lens.open || s.room != null) return
        if (lensHost == null) return set(s.copy(lensError = "no SpatialML host"), "lens unavailable")
        set(s.copy(lens = LensUi(open = true, opening = true), lensError = null, notice = null), "lens open ($why)")
        val spot = windowHost
        if (spot == null) {
            lensHost?.open()
            return
        }
        lensWaitingForSpot = true
        spot.openSpot(++spotGen)
        // the spot window reports focus within ~1 s; without it the lens still opens (where the system puts it)
        main.postDelayed({ lensSpotReady("no focus after $SPOT_WAIT_MS ms") }, SPOT_WAIT_MS)
    }

    /** The spot window is focused (or the wait is over): now the session, so its container lands in front of the spot. */
    fun lensSpotReady(why: String) {
        if (!lensWaitingForSpot || !s.lens.open) return
        lensWaitingForSpot = false
        FcLog.i("LENS spot ready ($why)")
        lensHost?.open()
    }

    fun lensSpotGone(gen: Int) {
        if (gen == spotGen && s.lens.open && !s.lens.reopening) closeLens("spot window closed", toSample = true)
    }

    fun lensReady() {
        if (!s.lens.open) return
        set(s.copy(lens = s.lens.copy(opening = false)), "lens ready")
        refreshPreview()
    }

    fun lensLive() {
        if (!s.lens.open || s.lens.live) return
        set(s.copy(lens = s.lens.copy(opening = false, live = true, reopening = false)), "lens live")
    }

    /**
     * Closes the viewfinder (design T3): cancel the loop, destroy the session on its own thread, close the spot. The
     * camera source goes back to the sample with a line (design R2-2).
     */
    fun closeLens(why: String, toSample: Boolean, kind: NoticeKind = NoticeKind.LENS_CLOSED_SAMPLE) {
        if (!s.lens.open) return
        lensWaitingForSpot = false
        lensHost?.close()
        windowHost?.closeSpot()
        val wasCamera = s.source == Source.CAMERA
        val n = if (wasCamera && toSample) notice(kind) else if (kind != NoticeKind.LENS_CLOSED_SAMPLE) notice(kind) else s.notice
        set(s.copy(lens = LensUi(), source = if (wasCamera) Source.SAMPLE else s.source, notice = n), "lens closed ($why)")
    }

    fun lensLost(why: String) {
        FcLog.w("LENS lost $why")
        closeLens("lost: $why", toSample = true, kind = NoticeKind.LENS_LOST)
    }

    fun lensFailed(reason: String) {
        FcLog.w("LENS failed $reason")
        lensWaitingForSpot = false
        lensHost?.close()
        windowHost?.closeSpot()
        set(s.copy(lens = LensUi(), lensError = reason, source = if (s.source == Source.CAMERA) Source.SAMPLE else s.source), "lens failed")
    }

    fun lensErrorOk() = set(s.copy(lensError = null), "lens error ok")

    private const val SPOT_WAIT_MS = 3_000L

    // =========================================================================================
    // the shutter (T5, S05 – S08f, S26)
    // =========================================================================================

    val shutterEnabled: Boolean
        get() = s.room == null && !s.reading && !s.flashing && !(s.noCamera && fakeReadback == null) && !s.lens.reopening

    fun shutterPressed(why: String = "tap") {
        if (s.countdown != null) {
            set(s.copy(countdown = null), "countdown cancelled ($why)")
            return
        }
        if (!shutterEnabled) return FcLog.i("EVENT shutter ignored ($why) state=${s.stateId}")
        if (s.timerOn) countdown(3) else fire(why)
    }

    private fun countdown(n: Int) {
        if (n == 0) {
            set(s.copy(countdown = null), "countdown done")
            fire("timer")
            return
        }
        play(SoundBank.Cue.BEEP)
        set(s.copy(countdown = n), "countdown $n")
        main.postDelayed({
            if (s.countdown != n) return@postDelayed
            if (held == "countdown") heldRelease.add { countdown(n - 1) } else countdown(n - 1)
        }, 1_000L)
    }

    private fun shotSeed(): Int = fixedSeed ?: random.nextInt(1, 1 shl 20)

    private fun fire(why: String) {
        val st = s
        FcLog.i("SHUTTER $why source=${st.source.id} filter=${st.filterId}")
        if (st.source == Source.CAMERA) return cameraShutter()
        val src = if (st.source == Source.SAMPLE) renderer.sample(st.sample, 1024) else importImg ?: return
        val look = lookOf(st, st.filterId, shotSeed())
        play(SoundBank.shutter(look.filter.sound))
        flash()
        scope.launch {
            val shot = withContext(Dispatchers.Default) { renderer.renderFull(src, look) }
            saveShot(shot, raw = null, look)
        }
    }

    /** White flash + frozen frame for 0.8 s (design L3 / S06): the live picture pauses meanwhile. */
    private fun flash() {
        set(s.copy(flashing = true), "flash")
        lensHost?.pause(true)
        main.postDelayed({
            val end: () -> Unit = {
                lensHost?.pause(false)
                set(s.copy(flashing = false), "flash end")
            }
            if (held == "flash") heldRelease.add(end) else end()
        }, FLASH_MS)
    }

    private suspend fun saveShot(shot: Img, raw: Img?, look: Look) {
        val st = s
        val stamp = photos.stamp()
        val id = look.filter.id.replace(':', '_')
        val comment = "FilterCam filter=${look.filter.id} strength=${(look.strength * 100).toInt()} seed=${look.seed}"
        val r =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = photos.save(shot, "FilterCam_${stamp}_$id.jpg", comment)
                    val rawUri = if (raw != null && st.saveRaw) runCatching { photos.save(raw, "FilterCam_${stamp}_raw.jpg", "FilterCam original") }.getOrNull() else null
                    uri to rawUri
                }
            }
        r.onSuccess { (uri, rawUri) ->
            play(SoundBank.Cue.CHIME)
            FcLog.i("SAVE ok $uri ${shot.w}x${shot.h} raw=$rawUri")
            val card = ShotCard(CardKind.SAVED, uri.toString(), rawUri?.toString(), look.filter.id, shot.w, shot.h, rawUri != null, serial = ++cardSerial)
            pendingRetry = null
            set(s.copy(card = card, lastShotUri = uri.toString(), galleryTick = s.galleryTick + 1, reading = false), "saved")
        }.onFailure {
            FcLog.e("SAVE failed: $it", it)
            pendingRetry = { scope.launch { saveShot(shot, raw, look) } }
            set(s.copy(card = ShotCard(CardKind.SAVE_FAILED, filterId = look.filter.id, serial = ++cardSerial), reading = false), "save failed")
        }
    }

    private var pendingRetry: (() -> Unit)? = null

    /** Camera shutter: permission card first (S07), then the read-back (S08). Fake on the emulator via `readback`. */
    private fun cameraShutter() {
        val st = s
        val fake = fakeReadback
        if (fake == "denied" || (st.permissionDenied && !st.permissionGranted && fake == null)) {
            set(s.copy(card = ShotCard(CardKind.VIEW_ONLY, filterId = st.filterId, serial = ++cardSerial)), "view only")
            return
        }
        if (!st.permissionGranted) {
            set(s.copy(overlay = Overlay.PERMISSION), "permission card")
            return
        }
        readBack()
    }

    fun permissionAllow() {
        set(s.copy(overlay = null), "permission allow")
        val fake = fakeReadback
        if (fake != null) return permissionResult(fake != "denied")
        permissionFor = "shutter"
        val ctx = appContext ?: return
        HelperActivity.launch(ctx, HelperActivity.MODE_PERMISSION)
    }

    /** What the pending CAMERA request is for: "shutter" (S07, read back after it) or "source" (camera picked, nothing after). */
    private var permissionFor: String? = null

    private fun cameraGranted(): Boolean = appContext?.let { HelperActivity.granted(it) } ?: s.permissionGranted

    /** The system CAMERA dialog for the camera source (「授权」 / 「再问一次」 on the preview go here too). */
    fun askCamera(why: String) {
        if (cameraGranted()) return permissionRecheck("ask: already granted")
        permissionFor = "source"
        FcLog.i("PERMISSION ask ($why)")
        val ctx = appContext ?: return
        HelperActivity.launch(ctx, HelperActivity.MODE_PERMISSION)
    }

    /** 「去设置」 after a refusal: the app's page in system settings; [permissionRecheck] on the way back. */
    fun openPermissionSettings() {
        FcLog.i("PERMISSION open settings")
        appContext?.let { HelperActivity.openAppSettings(it) }
    }

    /** The main window resumed (maybe back from Settings): pick up a permission granted or taken away there. */
    fun permissionRecheck(why: String) {
        val now = cameraGranted()
        if (now == s.permissionGranted) return
        set(s.copy(permissionGranted = now, permissionDenied = if (now) false else s.permissionDenied, permissionBlocked = if (now) false else s.permissionBlocked), "permission recheck $now ($why)")
    }

    fun permissionNotNow() = set(s.copy(overlay = null, permissionDenied = true, card = ShotCard(CardKind.VIEW_ONLY, filterId = s.filterId, serial = ++cardSerial)), "permission not now")

    fun permissionResult(granted: Boolean, canAskAgain: Boolean = true) {
        val forSource = permissionFor == "source"
        permissionFor = null
        if (forSource) {
            // camera picked: nothing to read back yet, only remember the answer (the preview shows 授权 / 去设置 until granted)
            set(s.copy(permissionGranted = granted, permissionDenied = !granted, permissionBlocked = !granted && !canAskAgain), "permission ${if (granted) "granted" else "denied"} (camera source)")
            return
        }
        if (!granted) {
            set(s.copy(permissionDenied = true, permissionBlocked = !canAskAgain, card = ShotCard(CardKind.VIEW_ONLY, filterId = s.filterId, serial = ++cardSerial)), "permission denied")
            return
        }
        set(s.copy(permissionGranted = true, permissionDenied = false, permissionBlocked = false), "permission granted")
        // design N16: if the permission only counts for sessions created after it, destroy and create again (S07r)
        if (fakeReadback == "sessionLevel" || (!s.isEmulator && SESSION_LEVEL_PERMISSION)) reopenThenRead() else readBack()
    }

    private fun reopenThenRead() {
        set(s.copy(lens = s.lens.copy(reopening = true, live = false)), "lens reopen (S07r)")
        lensHost?.close()
        main.postDelayed({
            val go: () -> Unit = {
                lensHost?.open()
                main.postDelayed({ readBack() }, REOPEN_MS)
            }
            if (held == "reopen") heldRelease.add(go) else go()
        }, 600L)
    }

    private fun readBack() {
        val st = s
        set(s.copy(reading = true, lens = s.lens.copy(reopening = false)), "reading (S08)")
        val look = lookOf(st, st.filterId, shotSeed())
        play(SoundBank.shutter(look.filter.sound))
        lensHost?.pause(true)
        scope.launch {
            if (held == "reading") {
                while (held == "reading") delay(100)
            }
            val fake = fakeReadback
            val raw: Img? =
                if (fake != null) {
                    FcLog.i("READBACK FAKE mode=$fake (emulator: the sample stands in for the camera frame)")
                    if (fake == "fail") null else renderer.sample(st.sample, LensController.W)
                } else lensHost?.still()?.let { Bitmaps.fromRgb(it, LensController.W, LensController.H) }
            lensHost?.pause(false)
            if (raw == null) {
                set(s.copy(reading = false, card = ShotCard(CardKind.READ_FAILED, filterId = st.filterId, serial = ++cardSerial)), "read-back failed")
                return@launch
            }
            val shot = withContext(Dispatchers.Default) { renderer.renderFull(raw, look) }
            saveShot(shot, raw, look)
        }
    }

    // ---- the shot card (P4)

    fun cardAgain() = set(s.copy(card = null), "card closed (again)")

    fun cardOk() = set(s.copy(card = null), "card ok")

    fun cardRetry() {
        val r = pendingRetry ?: return cardOk()
        set(s.copy(card = null), "save retry")
        r()
    }

    fun cardGrant() {
        val ctx = appContext ?: return
        set(s.copy(card = null), "card grant")
        if (fakeReadback != null) return permissionResult(true)
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { FcLog.w("SETTINGS open: $it") }
    }

    fun cardDeleteAsk(on: Boolean) = s.card?.let { set(s.copy(card = it.copy(deleteAsked = on)), "delete ask $on") }

    fun cardDeleteConfirm() {
        val c = s.card ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                c.uri?.let { photos.delete(it) }
                c.rawUri?.let { photos.delete(it) }
            }
            play(SoundBank.Cue.DELETE)
            set(s.copy(card = null, galleryTick = s.galleryTick + 1, page = if (c.fromGallery) Pages.GALLERY else s.page), "deleted ${c.uri}")
        }
    }

    /** 「换个滤镜重出」 (design S06 / M13): the original becomes the source (camera shots: the raw frame). */
    fun cardRedo() {
        val c = s.card ?: return
        val from = c.rawUri ?: c.uri
        set(s.copy(card = null), "redo")
        when {
            c.rawUri == null && !c.fromGallery && lastSourceWasSample -> set(s.copy(source = Source.SAMPLE, page = Pages.SHOOT), "redo from sample")
            from != null -> importFrom(Uri.parse(from), null)
        }
    }

    private val lastSourceWasSample: Boolean
        get() = s.source == Source.SAMPLE

    // ---- gallery (P8)

    fun galleryOpen(uri: String, raw: Boolean) = set(s.copy(card = ShotCard(CardKind.ITEM, uri = uri, filterId = if (raw) "raw" else s.filterId, fromGallery = true, serial = ++cardSerial)), "gallery item")

    fun galleryUse(uri: String) {
        set(s.copy(card = null), "gallery use")
        importFrom(Uri.parse(uri), null)
    }

    // ---- LUTs (T9)

    fun pickLut() {
        val ctx = appContext ?: return
        HelperActivity.launch(ctx, HelperActivity.MODE_LUT)
    }

    fun lutPicked(uri: Uri?) {
        uri ?: return
        val ctx = appContext ?: return
        scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val name = photos.displayName(uri).substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_ .-]"), "_").ifBlank { "lut" }
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw CubeError("no stream")
                        renderer.importLut(name, text)
                        name
                    }
                }
            r.onSuccess { importLutDone(it) }.onFailure {
                FcLog.w("LUT import failed: $it")
                set(s.copy(notice = notice(NoticeKind.LUT_BAD)), "lut bad")
            }
        }
    }

    /** Debug `lut --es path <file>`: import a .cube from the app's files or /sdcard. */
    fun lutFromFile(path: String) {
        scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val f = java.io.File(path)
                        renderer.importLut(f.name.substringBeforeLast('.'), f.readText())
                        f.name.substringBeforeLast('.')
                    }
                }
            r.onSuccess { importLutDone(it) }.onFailure {
                FcLog.w("LUT import failed $path: $it")
                set(s.copy(notice = notice(NoticeKind.LUT_BAD)), "lut bad")
            }
        }
    }

    private fun importLutDone(name: String) {
        val luts = renderer.importedLuts()
        renderer.buildThumbs(listOf(Filters.lutFilter(name)), null)
        set(s.copy(luts = luts, notice = notice(NoticeKind.LUT_ADDED, name)), "lut added $name")
    }

    fun lutDelete(name: String) {
        renderer.deleteLut(name)
        val id = FilterDef.LUT_PREFIX + name
        set(s.copy(luts = renderer.importedLuts(), filterId = if (s.filterId == id) Filters.F01.id else s.filterId), "lut deleted $name")
    }

    // ---- the tips card (P6, S00)

    fun guideOk() {
        prefs?.setGuideSeen(true)
        set(s.copy(overlay = null), "guide ok")
        // design entryFlow: after 「知道了」 the viewfinder opens by itself
        autoLens("guide ok")
    }

    /** The main window is up: the viewfinder opens with the camera page (design round 3), once per start, not over the guide. */
    fun windowReady() {
        if (autoLensDone) return
        autoLensDone = true
        if (s.overlay == null) autoLens("start")
    }

    private var autoLensDone = false

    private fun autoLens(why: String) {
        main.postDelayed({
            if (s.page != Pages.SHOOT || s.overlay != null || s.room != null || s.lens.open || s.lensError != null) return@postDelayed
            // design entryFlow: on the headset the viewfinder opens on the camera (asking for CAMERA first), on the emulator on the sample
            if (!s.isEmulator && !s.permissionBlocked && s.source == Source.SAMPLE) sourceSelected(Source.CAMERA, auto = true) else openLens("auto ($why)")
        }, AUTO_LENS_MS)
    }

    /** Set while the main window is swapped for a new one (「叫回面前」): its ON_STOP / ON_DESTROY are not the user leaving. */
    @Volatile var recalling = false

    /**
     * 「叫回面前」 in settings (D44, Full Space room mode makes this a Full Space app): the viewfinder closes (it hangs off the
     * old window), a new main window opens where the system puts new windows (in front of the head), the old one closes,
     * and the viewfinder comes back if it was open. No level gate here: the user is looking at this window when pressing
     * it, and in Shared Space there are no Stage frames to step the gate (it would wait 15 s).
     */
    fun recallFront() {
        val host = windowHost ?: return
        if (s.room != null) return
        val wasOpen = s.lens.open
        recalling = true
        closeLens("recall", toSample = false)
        set(s.copy(quitAsked = false, page = Pages.SETTINGS), "recall front")
        main.postDelayed({
            host.recallFront()
            main.postDelayed({
                recalling = false
                if (wasOpen) openLens("recall")
            }, RECALL_LENS_MS)
        }, RECALL_CLOSE_MS)
    }

    fun guideAgain() {
        prefs?.setGuideSeen(false)
        set(s.copy(overlay = Overlay.GUIDE, page = Pages.SHOOT), "guide again")
    }

    // =========================================================================================
    // room mode (T11 – T14, S14 – S16)
    // =========================================================================================

    fun roomTry() {
        if (s.room != null) return
        val d = def()
        val target = if (d.roomMode == RoomMode.NONE || !d.available) Filters.ROOM_FALLBACK else d.id
        val switched = if (target != d.id) d.id else null
        if (s.lens.open) {
            set(s.copy(lens = s.lens.copy(closing = true)), "lens closing for room (S14)")
            val go: () -> Unit = {
                closeLens("room", toSample = true)
                enterRoom(target, switched)
            }
            main.postDelayed({ if (held == "closing") heldRelease.add(go) else go() }, CLOSING_MS)
        } else enterRoom(target, switched)
    }

    private fun enterRoom(target: String, switched: String?) {
        set(s.copy(room = RoomUi(target, switched), card = null, overlay = null, filterId = target, stampOn = Filters.of(target)?.stampByDefault ?: s.stampOn, hoverId = null, compare = false), "room enter $target")
        windowHost?.openStage()
    }

    fun roomStep(step: Int, why: String = "button") {
        val r = s.room ?: return
        val next = Filters.roomStep(r.filterId, step)
        play(SoundBank.Cue.TICK)
        set(s.copy(room = r.copy(filterId = next, switchedFrom = null), filterId = next, stampOn = Filters.of(next)?.stampByDefault ?: s.stampOn), "room filter $next ($why)")
    }

    fun roomRecenter() = s.room?.let { set(s.copy(room = it.copy(recenter = it.recenter + 1)), "room recenter") }

    /** 「回取景台」: the main window comes back first (level head), then the Stage closes (design T14). */
    fun roomBack(why: String = "button") {
        if (s.room == null) return
        FcLog.i("ROOM back ($why)")
        val closer = stageCloser
        if (closer != null) closer() else stageClosed()
    }

    /** The Stage is gone (closed by the app, the system or the user). */
    fun stageClosed() {
        if (s.room == null) return
        set(s.copy(room = null, notice = notice(NoticeKind.RESUME)), "room exit (S16)")
    }

    fun stageResult(ok: Boolean, why: String) {
        FcLog.i("STAGE open $ok $why")
        if (!ok) set(s.copy(room = null), "stage refused")
    }

    // ---- the main window comes back only while the head is level (profile D9, Low Vision v0.1.5)

    private val windowGate = LevelGate()
    private var windowOpen: (() -> Unit)? = null
    private var gateLastStepMs = 0L

    fun openWhenLevel(why: String, open: () -> Unit) {
        windowGate.ask(why, now())
        windowOpen = open
        gateLastStepMs = 0L
        FcLog.i("LEVEL ask why=$why")
        main.removeCallbacks(gateWatchdog)
        main.postDelayed(gateWatchdog, GATE_WATCHDOG_MS)
    }

    fun cancelWindowGate(why: String) {
        if (!windowGate.waiting) return
        FcLog.i("LEVEL cancel ($why)")
        windowGate.cancel()
        windowOpen = null
        main.removeCallbacks(gateWatchdog)
    }

    /** Per Stage frame: [pitchDeg] of the head (+ up), null without a valid head pose. */
    fun stepWindowGate(now: Long, pitchDeg: Float?) {
        if (!windowGate.waiting) return
        gateLastStepMs = now
        windowGate.step(now, pitchDeg)?.let { fireWindowOpen(it) }
    }

    val levelHint: Int
        get() = 0

    fun levelHintFor(pitchDeg: Float?): Int = windowGate.hint(pitchDeg)

    private fun fireWindowOpen(why: String) {
        FcLog.i("LEVEL ok -> open why=$why")
        main.removeCallbacks(gateWatchdog)
        val open = windowOpen
        windowOpen = null
        open?.invoke()
    }

    private val gateWatchdog =
        object : Runnable {
            override fun run() {
                if (!windowGate.waiting) return
                val n = now()
                val stalled = if (gateLastStepMs == 0L) windowGate.waitedMs(n) > GATE_NO_FRAMES_FIRST_MS else n - gateLastStepMs > GATE_WATCHDOG_MS
                if (stalled) {
                    val why = windowGate.pending ?: return
                    windowGate.cancel()
                    fireWindowOpen("$why/noframes")
                } else main.postDelayed(this, GATE_WATCHDOG_MS)
            }
        }

    // =========================================================================================
    // lifecycle (S24) and quitting (S17 / S18)
    // =========================================================================================

    /** The main window went to the background (HOME, another app): the session goes (design S24). */
    fun appStopped() {
        if (s.lens.open) closeLens("background", toSample = true, kind = NoticeKind.RESUME)
        if (s.countdown != null) set(s.copy(countdown = null), "countdown stopped (background)")
    }

    fun quitClicked() = set(s.copy(quitAsked = true), "quit asked")

    fun quitCancelled() = set(s.copy(quitAsked = false), "quit cancelled")

    fun quitConfirmed() {
        FcLog.i("QUIT confirmed")
        lensHost?.close()
        windowHost?.closeSpot()
        stageCloser?.invoke()
        val ctx = appContext
        val tasks = ctx?.getSystemService(android.app.ActivityManager::class.java)?.appTasks.orEmpty()
        tasks.forEach { t -> runCatching { t.finishAndRemoveTask() }.onFailure { FcLog.w("QUIT finishAndRemoveTask: $it") } }
        FcLog.i("QUIT removed ${tasks.size} task(s), process ends")
        sounds?.release()
        main.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }, QUIT_EXIT_DELAY_MS)
    }

    // =========================================================================================
    // sound and debug hooks
    // =========================================================================================

    fun play(cue: SoundBank.Cue) = sounds?.play(cue, s.muted, s.volume)

    fun hold(what: String?) {
        held = what
        FcLog.i("HOLD ${what ?: "none"}")
    }

    fun release() {
        held = null
        val todo = ArrayList(heldRelease)
        heldRelease.clear()
        todo.forEach { it() }
        FcLog.i("HOLD released ${todo.size}")
    }

    fun seed(n: Int?) {
        fixedSeed = n
        if (n != null) liveSeed = n
        refreshPreview()
    }

    /** Debug `state`: jump the store to a design state through the same functions as taps. */
    fun debugSet(next: FcState, why: String) = set(next, why)

    fun dump(): String {
        val st = s
        return "DUMP state=${st.stateId} page=${st.page} source=${st.source.id} sample=${st.sample} import=${st.importName} filter=${st.filterId} hover=${st.hoverId} strength=${st.strength} " +
            "stamp=${st.stampOn}/${st.stampFormat.id} compare=${st.compare} timer=${st.timerOn} lens=${st.lens} lensError=${st.lensError} card=${st.card?.kind}/${st.card?.uri} " +
            "room=${st.room} muted=${st.muted} vol=${st.volume} lang=${st.lang} luts=${st.luts} permission=${st.permissionGranted}/${st.permissionDenied} fake=$fakeReadback held=$held last=${st.lastShotUri}"
    }

    private const val FLASH_MS = 800L
    private const val CLOSING_MS = 700L
    private const val REOPEN_MS = 2_500L
    private const val QUIT_EXIT_DELAY_MS = 600L
    private const val GATE_WATCHDOG_MS = 3_000L
    private const val GATE_NO_FRAMES_FIRST_MS = 15_000L
    private const val AUTO_LENS_MS = 1_200L
    private const val RECALL_CLOSE_MS = 400L
    private const val RECALL_LENS_MS = 1_500L

    /** Design N16, unknown until the headset spike: assume the permission is checked at read-back time. */
    private const val SESSION_LEVEL_PERMISSION = false
}
