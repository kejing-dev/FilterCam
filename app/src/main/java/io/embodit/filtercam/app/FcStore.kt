package io.embodit.filtercam.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.filtercam.R
import io.embodit.filtercam.domain.AlbumNav
import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.CubeError
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.domain.Img
import io.embodit.filtercam.domain.LensPolicy
import io.embodit.filtercam.domain.LevelGate
import io.embodit.filtercam.domain.Look
import io.embodit.filtercam.domain.PermCard
import io.embodit.filtercam.domain.RoomMode
import io.embodit.filtercam.domain.ShotQueue
import io.embodit.filtercam.domain.SideWindow
import io.embodit.filtercam.domain.Stamp
import io.embodit.filtercam.domain.StampFormat
import io.embodit.filtercam.platform.Bitmaps
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.GalleryItem
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one state holder of the process: the camera window W1, the filter window W2, the room Stage and the debug receiver
 * all call these functions on the main thread (debug commands take the same paths as taps, profile D29). Per-frame work
 * (camera frames, 3D) never goes through [state]; frames go to [Renderer.live], the room scene reports discrete events.
 *
 * v0.2.0 (design round 4): the viewfinder is drawn inside W1 from read-back camera frames; whether its session runs is
 * one rule ([LensPolicy], [syncLens]) checked after every change, instead of the open / close calls of v0.1.x.
 */
object FcStore {
    /** The read-back viewfinder session, see [LensController]. */
    interface LensHost {
        val isOpen: Boolean

        fun open()

        fun close()

        fun pause(on: Boolean)
    }

    /** Opens / closes system windows (lent by the camera window, which owns the navigator). */
    interface WindowHost {
        fun openSide()

        fun closeSide()

        fun openStage()

        fun closeStage()

        /** 「叫回面前」: the camera window again where the system opens a new one (in front of the head), this one closed. */
        fun recallFront()

        /** Quit: W2 and this window closed (an open window container keeps an empty task in recents). */
        fun closeAll()
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val _state = MutableStateFlow(FcState())
    val state: StateFlow<FcState> = _state.asStateFlow()

    /** The picture held still after the shutter (S06 freeze) and then flown into the corner; also the thumbnail until the saved file loads. */
    private val _shotPicture = MutableStateFlow<Bitmap?>(null)
    val shotPicture: StateFlow<Bitmap?> = _shotPicture.asStateFlow()

    var lensHost: LensHost? = null
    var windowHost: WindowHost? = null

    /** Closes the room Stage from inside it (the camera window is closed while the room is on). */
    var stageCloser: (() -> Unit)? = null

    /** The camera window closed itself for room mode; the Stage opens it again when it ends. */
    @Volatile var hiddenHome: Pair<String, String?>? = null

    /** Lifecycle of the camera window as it reports it (ON_CREATE … ON_DESTROY, "never"). */
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

    /** The look camera frames are rendered with (read on the frame thread). */
    @Volatile private var liveLook: Look? = null

    /** The imported photo at 1024 x 768 (design S09). */
    private var importImg: Img? = null

    /** Debug `hold --es state …` (design qaHooks, D36): a transition under 3 s stays until `release`. */
    @Volatile var held: String? = null
        private set
    private val heldRelease = ArrayList<() -> Unit>()

    /** Debug `readback --es mode ok|fail` (design qaHooks): ok = sample frames stand in for the camera on the emulator (log FAKE); fail = the next camera shot is not caught. */
    @Volatile var fakeReadback: String? = null
        private set
    private var fakeFeed: Job? = null

    private var noticeSerial = 0
    private var thumbSerial = 0

    /** The camera window is started (not in the background): the session only runs then. */
    private var windowInFront = false

    /** The session ended by leaving (background, room, album/settings never close it): say 「取景回来了」 when frames come back. */
    private var announceBack = false
    private var lossesInARow = 0

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
        val missing = HelperActivity.missing(context)
        // design r4 S00 / S01: a cold start goes to the camera, the permission card first if one is missing; the emulator
        // with both permissions has no camera picture, so it starts on the sample (S01s)
        val source = if (isEmulator && missing.isEmpty()) Source.SAMPLE else Source.CAMERA
        start = start.copy(source = source, missingPerms = missing, permissionDenied = start.permissionDenied && missing.isNotEmpty(), blockedPerms = start.blockedPerms intersect missing)
        _state.value = start
        FcLog.i("STATE ${start.stateId} init filter=${start.filterId} source=${source.id} lang=${start.lang} emulator=$isEmulator luts=${luts.size} missing=${missing.map { it.name }}")
        refreshLook()
        renderer.buildThumbs(renderer.allFilters(), stampTextFor(StampFormat.SHORT))
        scope.launch { loadLatestThumb() }
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
                prev.luts != next.luts || prev.lang != next.lang
        if (visual) refreshLook()
        syncLens(why)
    }

    fun t(id: Int, vararg args: Any): String {
        val ctx = appContext ?: return ""
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(if (s.lang == Lang.EN) java.util.Locale.ENGLISH else java.util.Locale.SIMPLIFIED_CHINESE)
        return ctx.createConfigurationContext(cfg).resources.getString(id, *args)
    }

    // =========================================================================================
    // looks and the viewfinder picture
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

    /** The picture of a photo source at [width] (4:3), or null for the camera. */
    private fun sourceImg(st: FcState, width: Int): Img? =
        when (st.source) {
            Source.SAMPLE -> renderer.sample(st.sample, width)
            Source.IMPORT -> importImg?.let { if (it.w == width) it else it.resized(width, width * it.h / it.w) }
            Source.CAMERA -> null
        }

    /** The look changed: camera frames pick it up with the next frame; photo sources render once. */
    private fun refreshLook() {
        val st = s
        // a LUT parses in up to ~1.3 s on first use: never on the main thread (the first-tap rule)
        val gen = ++lookGen
        scope.launch(Dispatchers.Default) {
            val look = lookOf(st, st.previewFilter, liveSeed)
            if (gen == lookGen) liveLook = look
        }
        if (st.source != Source.CAMERA) renderer.requestPreview(src = { sourceImg(st, Renderer.PREVIEW_W) }, look = { lookOf(st, st.previewFilter, liveSeed) })
    }

    @Volatile private var lookGen = 0

    /** A read-back camera frame (640 x 480 RGB, any thread): through the current look into the viewfinder. */
    fun onCameraFrame(rgb: ByteArray) {
        val look = liveLook ?: return
        renderer.renderLive(rgb, LensController.W, LensController.H, look)
        if (!s.lens.live) main.post { lensLive() }
    }

    // ---- the camera permissions (profile D58, design r4 S00 – S00c): each named on its own, Spatial Data first

    /** A permission's name as the app says it ([settings]: as PICO OS Settings lists it). */
    fun permName(p: CamPerm, settings: Boolean = false): String =
        when (p) {
            CamPerm.CAMERA -> t(R.string.perm_camera)
            CamPerm.SPATIAL_DATA -> t(if (settings) R.string.perm_spatial_settings else R.string.perm_spatial)
        }

    fun permWhy(p: CamPerm): String = t(if (p == CamPerm.SPATIAL_DATA) R.string.perm_spatial_why else R.string.perm_camera_why)

    /** 「开始授权」 / 「再问一次」: the system dialogs one by one, Spatial Data first (only the missing ones). */
    fun startPermissions(why: String) {
        val missing = missingNow()
        if (missing.isEmpty()) return permissionRecheck("ask: already granted")
        fakeMissing?.let { return permissionResult(it, s.blockedPerms) }
        FcLog.i("PERMISSION ask ${PermCard.askOrder(missing, s.blockedPerms).map { it.name }} ($why)")
        set(s.copy(permWaiting = false), "permission start ($why)")
        val ctx = appContext ?: return
        HelperActivity.launch(ctx, HelperActivity.MODE_PERMISSION)
    }

    /** The helper is about to show the dialog for [p] (S00a 「正在问 n/2」). */
    fun permissionAsking(p: CamPerm?) = set(s.copy(asking = p), "permission asking ${p?.name}")

    /** 「去设置」: this app's page in system settings; the card waits (S00c) and re-checks every second. */
    fun openPermissionSettings() {
        FcLog.i("PERMISSION open settings")
        set(s.copy(permWaiting = true), "permission wait settings")
        if (fakeMissing == null) appContext?.let { HelperActivity.openAppSettings(it) }
    }

    /** Debug `perm --es step …`: the card's states without system dialogs (null = the real permissions). */
    @Volatile var fakeMissing: Set<CamPerm>? = null

    /** CAMERA and SPATIAL_DATA, each checked on its own (profile D58). */
    private fun missingNow(): Set<CamPerm> = fakeMissing ?: appContext?.let { HelperActivity.missing(it) } ?: s.missingPerms

    /**
     * Resume, focus, or the card's 1 s poll (Shared Space opens Settings beside the window, no pause): pick up each
     * permission granted or taken away there. Both on: the session starts right here ([syncLens]), no restart.
     */
    fun permissionRecheck(why: String) {
        val now = missingNow()
        if (now == s.missingPerms) return
        FcLog.i("PERM recheck missing=${now.map { it.name }} ${if (now.isEmpty()) "granted" else ""} ($why)")
        set(s.copy(missingPerms = now, permissionDenied = s.permissionDenied && now.isNotEmpty(), blockedPerms = s.blockedPerms intersect now, permWaiting = s.permWaiting && now.isNotEmpty()), "permission recheck ($why)")
    }

    /** The dialogs answered: [missing] is what is still off, [blocked] the ones refused for good (USER_FIXED included). */
    fun permissionResult(missing: Set<CamPerm>, blocked: Set<CamPerm>) {
        val granted = missing.isEmpty()
        val nextBlocked = (s.blockedPerms + blocked) intersect missing
        set(
            s.copy(missingPerms = missing, permissionDenied = !granted, blockedPerms = nextBlocked, asking = null, permWaiting = false),
            "permission ${if (granted) "granted" else "missing"} missing=${missing.map { it.name }} blocked=${nextBlocked.map { it.name }}",
        )
    }

    // =========================================================================================
    // the viewfinder session (design r4 container.lifecycle, S01 / S21 / S22 / S24 / S25)
    // =========================================================================================

    private var syncing = false

    /** Opens, pauses or closes the read-back session so it matches [LensPolicy] (called after every change). */
    private fun syncLens(why: String) {
        if (syncing) return
        syncing = true
        try {
            val st = s
            val fake = (fakeReadback == "ok" || fakeReadback == "fail") && st.isEmulator
            val want = LensPolicy.want(st.source == Source.CAMERA, st.permissionGranted, !st.isEmulator || fake, windowInFront, st.room != null, st.lensError != null, st.page == Pages.SHOOT)
            val host = lensHost
            val running = if (fake) fakeFeed?.isActive == true else host?.isOpen == true
            when (want) {
                LensPolicy.Want.CLOSED ->
                    if (running || st.lens.open) {
                        stopLens()
                        if (st.source == Source.CAMERA && st.permissionGranted && st.lensError == null) announceBack = true
                        set(s.copy(lens = LensUi()), "lens closed ($why)")
                    }
                LensPolicy.Want.OPEN, LensPolicy.Want.PAUSED -> {
                    if (!running && !st.lens.reopening) {
                        if (fake) startFakeFeed() else if (host != null) host.open() else return set(s.copy(lensError = "no SpatialML host"), "lens unavailable")
                        set(s.copy(lens = LensUi(open = true, opening = true)), "lens open ($why)")
                    }
                    host?.pause(want == LensPolicy.Want.PAUSED)
                    fakePaused = want == LensPolicy.Want.PAUSED
                }
            }
        } finally {
            syncing = false
        }
    }

    private fun stopLens() {
        lensHost?.close()
        fakeFeed?.cancel()
        fakeFeed = null
        renderer.clearLive()
    }

    @Volatile private var fakePaused = false

    /** Emulator, debug `readback --es mode ok`: the sample photo stands in for camera frames at ~20 Hz (above the target, so the render ceiling shows), same path after the read-back. */
    private fun startFakeFeed() {
        fakeFeed?.cancel()
        FcLog.i("READBACK FAKE feed on (emulator: the sample stands in for the camera frames)")
        fakeFeed =
            scope.launch {
                val frame = withContext(Dispatchers.Default) { Bitmaps.rgbBytes(renderer.sample(s.sample, LensController.W), LensController.W, LensController.H) }
                while (isActive) {
                    if (!fakePaused) onCameraFrame(frame)
                    delay(FAKE_FRAME_MS)
                }
            }
    }

    fun lensLive() {
        if (!s.lens.open || s.lens.live) return
        lossesInARow = 0
        val n =
            when {
                prefs?.hintSeen() == false -> notice(NoticeKind.HINT).also { prefs?.setHintSeen(true) }
                announceBack || s.lens.reopening -> notice(NoticeKind.LENS_BACK)
                else -> s.notice
            }
        announceBack = false
        set(s.copy(lens = s.lens.copy(opening = false, live = true, reopening = false), notice = n), "lens live")
    }

    /** S25: the session died; reopen once by itself, a second loss in a row ends on S21. */
    fun lensLost(why: String) {
        FcLog.w("LENS lost $why")
        lossesInARow++
        stopLens()
        announceBack = true
        if (!LensPolicy.afterLoss(lossesInARow)) return lensFailed("lost twice: $why")
        set(s.copy(lens = LensUi(open = true, opening = true, reopening = true), notice = notice(NoticeKind.LENS_LOST)), "lens lost, reopening")
        main.postDelayed({
            val go: () -> Unit = {
                if (s.lens.reopening) {
                    set(s.copy(lens = LensUi()), "lens reopen")
                }
            }
            if (held == "lost") heldRelease.add(go) else go()
        }, REOPEN_GAP_MS)
    }

    fun lensFailed(reason: String) {
        FcLog.w("LENS failed $reason")
        stopLens()
        set(s.copy(lens = LensUi(), lensError = reason), "lens failed")
    }

    /** S21 「再试一次」. */
    fun lensRetry() {
        lossesInARow = 0
        set(s.copy(lensError = null), "lens retry")
    }

    // =========================================================================================
    // camera window controls (design T1 – T7)
    // =========================================================================================

    fun pageSelected(page: String) {
        if (page == Pages.GALLERY) return albumOpen("tab")
        set(s.copy(page = page, quitAsked = false, hoverId = null, albumDeleteAsk = false, moreOpen = if (page == Pages.SHOOT) s.moreOpen else false), "page $page")
    }

    /** Whether W2 is on screen (design W2.visibility); the camera window opens / closes it to match. */
    val sideShown: Boolean
        get() = s.let { SideWindow.shown(it.sideWanted, it.page == Pages.SHOOT, it.permissionCard, it.room != null, it.quitAsked) }

    /** W1 「滤镜」: fold / unfold W2 (unfolded at Placement.right(W1) again). */
    fun sideToggled(open: Boolean = !sideShown) = set(s.copy(sideWanted = open, moreOpen = false, hoverId = null, page = Pages.SHOOT), "side ${if (open) "open" else "fold"}")

    /** W2 closed from outside (the window's ×): folded, W1 「滤镜」 brings it back. */
    fun sideClosedBySystem() {
        if (!s.sideWanted) return
        set(s.copy(sideWanted = false, moreOpen = false, hoverId = null), "side closed by its ×")
    }

    /** W2 「更多」 / 「收起」 (S29). */
    fun moreToggled(open: Boolean = !s.moreOpen) = set(s.copy(moreOpen = open, hoverId = null), "more $open")

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

    /** Design T4 (W2 更多 · 拍什么, the card's 「先用示例照片玩」, S22 「用示例照片」, S01s 「要拍真房间：开权限」). */
    fun sourceSelected(src: Source) {
        when (src) {
            Source.SAMPLE -> set(s.copy(source = Source.SAMPLE, notice = null, permWaiting = false, permReview = false, page = Pages.SHOOT), "source sample")
            Source.CAMERA -> set(s.copy(source = Source.CAMERA, notice = null, page = Pages.SHOOT, lensError = null), "source camera")
            Source.IMPORT -> {
                if (importImg != null && s.importName != null) set(s.copy(source = Source.IMPORT, notice = null), "source import (kept)")
                pickPhoto()
            }
        }
    }

    /** Settings 「重看权限说明」: the camera page with the permission card (both on: the card says so, S00 with ✓ ✓). */
    fun permReviewOpen() = set(s.copy(page = Pages.SHOOT, source = Source.CAMERA, quitAsked = false, permReview = true), "permission card again")

    fun permReviewDone() = set(s.copy(permReview = false), "permission card ok")

    fun pickPhoto() {
        val ctx = appContext ?: return
        FcLog.i("EVENT picker photo (S09p)")
        HelperActivity.launch(ctx, HelperActivity.MODE_PHOTO)
    }

    /** Debug `import --es fail bad` (design S27): the next import fails. */
    @Volatile var failNextImport = false

    fun photoPicked(uri: Uri?) {
        if (uri == null) return FcLog.i("EVENT picker cancelled")
        importFrom(uri, null)
    }

    /** Loads a picture as the 「我的照片」 source (picker, the album's 「当素材拍」). */
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

    fun noticeDismissed(serial: Int) {
        if (s.notice?.serial == serial) set(s.copy(notice = null), "notice dismissed")
    }

    // =========================================================================================
    // the shutter (T5, S05 / S06 / S08 / S08f / S26): flash, freeze, fly into the corner; no card
    // =========================================================================================

    val shutterEnabled: Boolean
        get() = s.let { it.room == null && it.page == Pages.SHOOT && ShotQueue.canShoot(it.saving, it.flashing) && !it.permissionCard && it.lensError == null && (!it.noCamera || fakeReadback != null) }

    fun shutterPressed(why: String = "tap") {
        if (s.countdown != null) {
            set(s.copy(countdown = null), "countdown cancelled ($why)")
            return
        }
        if (!shutterEnabled) return FcLog.i("EVENT shutter ignored ($why) state=${s.stateId} saving=${s.saving}")
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
        val look = lookOf(st, st.filterId, shotSeed())
        FcLog.i("SHUTTER $why source=${st.source.id} filter=${st.filterId}")
        if (st.source == Source.CAMERA) {
            // the frame on screen is the frame saved (design r4: read-back mode, same engine): the newest read-back frame
            val raw = renderer.live.value?.raw
            if (raw == null || fakeReadback == "fail") {
                FcLog.w("READBACK no frame for the shutter (${if (raw == null) "no frame yet" else "debug fail"})")
                set(s.copy(notice = notice(NoticeKind.READ_FAILED)), "read-back failed (S08f)")
                return
            }
            play(SoundBank.shutter(look.filter.sound))
            scope.launch {
                val shot = withContext(Dispatchers.Default) { renderer.renderFull(raw, look) }
                flashAndFly(Bitmaps.toBitmap(shot))
                saveShot(shot, raw, look)
            }
            return
        }
        val src = (if (st.source == Source.SAMPLE) renderer.sample(st.sample, 1024) else importImg) ?: return
        play(SoundBank.shutter(look.filter.sound))
        // the picture on screen freezes at once; the 1024 version renders meanwhile
        flashAndFly(renderer.preview.value)
        scope.launch {
            val shot = withContext(Dispatchers.Default) { renderer.renderFull(src, look) }
            saveShot(shot, null, look)
        }
    }

    /** S06: white flash 120 ms + frozen frame 300 ms, then the window flies [picture] into the thumbnail (350 ms). */
    private fun flashAndFly(picture: Bitmap?) {
        _shotPicture.value = picture
        set(s.copy(flashing = true, saving = s.saving + 1, thumb = ShotThumb(++thumbSerial)), "flash")
        main.postDelayed({
            val end: () -> Unit = { set(s.copy(flashing = false), "flash end (fly)") }
            if (held == "flash") heldRelease.add(end) else end()
        }, FLASH_MS + FREEZE_MS)
    }

    private suspend fun saveShot(shot: Img, raw: Img?, look: Look) {
        if (held == "reading") while (held == "reading") delay(100)
        val st = s
        val stamp = photos.stamp()
        val id = look.filter.id.replace(':', '_')
        val comment = "FilterCam filter=${look.filter.id} strength=${(look.strength * 100).toInt()} seed=${look.seed}"
        val serial = thumbSerial
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
            pendingRetry = null
            val thumb = if (s.thumb?.serial == serial) ShotThumb(serial, uri.toString()) else s.thumb
            set(s.copy(saving = (s.saving - 1).coerceAtLeast(0), thumb = thumb, galleryTick = s.galleryTick + 1), "saved")
        }.onFailure {
            FcLog.e("SAVE failed: $it", it)
            pendingRetry = { scope.launch { saveShot(shot, raw, look) } }
            set(s.copy(saving = (s.saving - 1).coerceAtLeast(0), thumb = s.thumb?.copy(failed = true), notice = notice(NoticeKind.SAVE_FAILED)), "save failed (S26)")
        }
    }

    private var pendingRetry: (() -> Unit)? = null

    /** S26 toast 「再试一次」. */
    fun saveRetry() {
        val r = pendingRetry ?: return
        pendingRetry = null
        set(s.copy(saving = s.saving + 1, notice = null, thumb = s.thumb?.copy(failed = false)), "save retry")
        r()
    }

    /** The thumbnail at start: the newest photo in the album, if any. */
    private suspend fun loadLatestThumb() {
        val first = withContext(Dispatchers.IO) { albumItems().firstOrNull() } ?: return
        if (s.thumb == null) set(s.copy(thumb = ShotThumb(0, first.uri)), "thumb newest")
    }

    // =========================================================================================
    // the album (design W1.pages.album, S12 / S12e / S13)
    // =========================================================================================

    /** Filtered shots, newest first (originals stay in the system gallery, design c4-5). */
    fun albumItems(): List<GalleryItem> = photos.query().filter { !it.raw }.sortedByDescending { it.name }

    /** The thumbnail or the 「相册」 tab: straight to the newest photo (design B31: no stop on a confirm card). */
    fun albumOpen(why: String) {
        set(s.copy(page = Pages.GALLERY, albumIndex = 0, albumDeleteAsk = false, quitAsked = false, hoverId = null, moreOpen = false, countdown = null), "album open ($why)")
    }

    fun albumSelect(i: Int, count: Int) = set(s.copy(albumIndex = i.coerceIn(0, (count - 1).coerceAtLeast(0)), albumDeleteAsk = false), "album $i")

    fun albumStep(by: Int, count: Int) = albumSelect(AlbumNav.step(s.albumIndex, by, count), count)

    fun albumDeleteAsk(on: Boolean) = set(s.copy(albumDeleteAsk = on), "album delete ask $on")

    /** S13 「删掉」: the photo and its original go; the next one shows (none left: S12e). */
    fun albumDeleteConfirm() {
        val i = s.albumIndex
        scope.launch {
            val all = withContext(Dispatchers.IO) { photos.query() }
            val shots = all.filter { !it.raw }.sortedByDescending { it.name }
            val item = shots.getOrNull(i) ?: return@launch set(s.copy(albumDeleteAsk = false), "album delete: nothing at $i")
            withContext(Dispatchers.IO) {
                photos.delete(item.uri)
                val stamp = item.name.removeSuffix(".jpg").substringBeforeLast('_')
                all.firstOrNull { it.raw && it.name == "${stamp}_raw.jpg" }?.let { photos.delete(it.uri) }
            }
            play(SoundBank.Cue.DELETE)
            val next = AlbumNav.afterDelete(i, shots.size)
            val thumb = if (s.thumb?.uri == item.uri) shots.filter { it.uri != item.uri }.firstOrNull()?.let { ShotThumb(s.thumb!!.serial, it.uri) } else s.thumb
            if (thumb == null || thumb.uri == null) _shotPicture.value = null
            set(s.copy(albumIndex = next, albumDeleteAsk = false, galleryTick = s.galleryTick + 1, thumb = thumb), "album deleted ${item.name}")
        }
    }

    /** 「当素材拍」: this photo becomes the 「我的照片」 source on the camera page. */
    fun albumUse(uri: String) = importFrom(Uri.parse(uri), null)

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
        set(s.copy(luts = luts, moreOpen = false, notice = notice(NoticeKind.LUT_ADDED, name)), "lut added $name")
    }

    fun lutDelete(name: String) {
        renderer.deleteLut(name)
        val id = FilterDef.LUT_PREFIX + name
        set(s.copy(luts = renderer.importedLuts(), filterId = if (s.filterId == id) Filters.F01.id else s.filterId), "lut deleted $name")
    }

    /** Set while the camera window is swapped for a new one (「叫回面前」): its ON_STOP / ON_DESTROY are not the user leaving. */
    @Volatile var recalling = false

    /**
     * 「叫回面前」 in settings (D44, Full Space room mode makes this a Full Space app): W2 folds away, a new camera window
     * opens where the system puts new windows (in front of the head), the old one closes; W2 comes back beside the new one.
     */
    fun recallFront() {
        val host = windowHost ?: return
        if (s.room != null) return
        recalling = true
        host.closeSide()
        set(s.copy(quitAsked = false, page = Pages.SETTINGS), "recall front")
        main.postDelayed({
            host.recallFront()
            main.postDelayed({ recalling = false }, RECALL_SETTLE_MS)
        }, RECALL_CLOSE_MS)
    }

    // =========================================================================================
    // room mode (T11 – T14, S14 – S16)
    // =========================================================================================

    fun roomTry() {
        if (s.room != null) return
        val d = def()
        val target = if (d.roomMode == RoomMode.NONE || !d.available) Filters.ROOM_FALLBACK else d.id
        val switched = if (target != d.id) d.id else null
        // the session closes and W2 folds by their rules (LensPolicy / SideWindow: in room)
        set(s.copy(room = RoomUi(target, switched), moreOpen = false, filterId = target, stampOn = Filters.of(target)?.stampByDefault ?: s.stampOn, hoverId = null, compare = false, countdown = null), "room enter $target")
        windowHost?.closeSide()
        windowHost?.openStage()
    }

    fun roomStep(step: Int, why: String = "button") {
        val r = s.room ?: return
        val next = Filters.roomStep(r.filterId, step)
        play(SoundBank.Cue.TICK)
        set(s.copy(room = r.copy(filterId = next, switchedFrom = null), filterId = next, stampOn = Filters.of(next)?.stampByDefault ?: s.stampOn), "room filter $next ($why)")
    }

    fun roomRecenter() = s.room?.let { set(s.copy(room = it.copy(recenter = it.recenter + 1)), "room recenter") }

    /** 「回去拍照」: the camera window comes back first (level head), then the Stage closes (design T14). */
    fun roomBack(why: String = "button") {
        if (s.room == null) return
        FcLog.i("ROOM back ($why)")
        val closer = stageCloser
        if (closer != null) closer() else stageClosed()
    }

    /** The Stage is gone (closed by the app, the system or the user): back to the camera page (S16). */
    fun stageClosed() {
        if (s.room == null) return
        if (s.source == Source.CAMERA) announceBack = true
        set(s.copy(room = null, page = Pages.SHOOT), "room exit (S16)")
    }

    fun stageResult(ok: Boolean, why: String) {
        FcLog.i("STAGE open $ok $why")
        if (!ok) set(s.copy(room = null), "stage refused")
    }

    // ---- the camera window comes back only while the head is level (profile D9, Low Vision v0.1.5)

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

    /** The camera window is started / resumed: the session comes back by itself (S24), permissions are re-checked. */
    fun windowInFront(why: String) {
        windowInFront = true
        permissionRecheck(why)
        syncLens(why)
    }

    /** The camera window went to the background (HOME, another app, the system's permission dialog): the session goes. */
    fun windowStopped() {
        windowInFront = false
        if (s.countdown != null) set(s.copy(countdown = null), "countdown stopped (background)")
        syncLens("background")
    }

    fun quitClicked() = set(s.copy(quitAsked = true), "quit asked")

    fun quitCancelled() = set(s.copy(quitAsked = false), "quit cancelled")

    fun quitConfirmed() {
        FcLog.i("QUIT confirmed")
        stopLens()
        stageCloser?.invoke()
        recalling = true // the windows' ON_STOP / ON_DESTROY below are the quit, not the user leaving
        windowHost?.closeAll()
        sounds?.release()
        main.postDelayed({
            val tasks = appContext?.getSystemService(android.app.ActivityManager::class.java)?.appTasks.orEmpty()
            tasks.forEach { t -> runCatching { t.finishAndRemoveTask() }.onFailure { FcLog.w("QUIT finishAndRemoveTask: $it") } }
            FcLog.i("QUIT removed ${tasks.size} task(s), process ends")
        }, QUIT_CLOSE_MS)
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
        refreshLook()
    }

    /** Debug `readback --es mode …`: changes the fake camera, then the session rule runs again. */
    fun fakeReadbackMode(mode: String?) {
        fakeReadback = mode
        stopLens()
        set(s.copy(lens = LensUi()), "debug readback $mode")
    }

    /** Debug `state`: jump the store to a design state through the same functions as taps. */
    fun debugSet(next: FcState, why: String) = set(next, why)

    fun dump(): String {
        val st = s
        val lens = lensHost as? LensController
        return "DUMP state=${st.stateId} page=${st.page} source=${st.source.id} sample=${st.sample} import=${st.importName} filter=${st.filterId} hover=${st.hoverId} strength=${st.strength} " +
            "stamp=${st.stampOn}/${st.stampFormat.id} compare=${st.compare} timer=${st.timerOn} lens=${st.lens} lensError=${st.lensError} readbackHz=${"%.1f".format(lens?.hz ?: 0f)} pipelined=${lens?.pipelined} " +
            "viewfinderHz=${"%.1f".format(renderer.liveHz)} renderMs=${"%.0f".format(renderer.liveRenderMs)} side=${sideShown}/wanted=${st.sideWanted} more=${st.moreOpen} saving=${st.saving} thumb=${st.thumb} album=${st.albumIndex}/ask=${st.albumDeleteAsk} " +
            "room=${st.room} muted=${st.muted} vol=${st.volume} lang=${st.lang} luts=${st.luts} permission=missing${st.missingPerms.map { it.name }}/denied=${st.permissionDenied}/blocked${st.blockedPerms.map { it.name }}/asking=${st.asking}/waiting=${st.permWaiting} " +
            "front=$windowInFront fake=$fakeReadback held=$held"
    }

    private const val FLASH_MS = 120L
    private const val FREEZE_MS = 300L
    private const val QUIT_CLOSE_MS = 300L
    private const val QUIT_EXIT_DELAY_MS = 900L
    private const val GATE_WATCHDOG_MS = 3_000L
    private const val GATE_NO_FRAMES_FIRST_MS = 15_000L
    private const val RECALL_CLOSE_MS = 400L
    private const val RECALL_SETTLE_MS = 1_500L
    private const val REOPEN_GAP_MS = 600L
    private const val FAKE_FRAME_MS = 50L
}
