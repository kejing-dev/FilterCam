package io.embodit.filtercam.debug

import android.os.Handler
import android.os.Looper
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.Lang
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.app.Source
import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.StampFormat
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.LensController
import io.embodit.filtercam.ui.room.ActiveRoom

/**
 * Debug commands (design r4 qaHooks). Every command goes through the same store functions as the buttons and logs
 * `CMD ok …` / `CMD fail …`; a `state` jump logs `CMD reached <id>` once the store says it is there. List in AGENTS.md.
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(cmd: String, a: Map<String, String>) {
        val ok = runCatching { exec(cmd, a) }.onFailure { FcLog.e("CMD fail $cmd: $it", it) }.getOrDefault(false)
        FcLog.i("CMD ${if (ok) "ok" else "fail"} $cmd ${a.values.joinToString(" ")}")
    }

    private fun s() = FcStore.state.value

    private fun lens() = FcStore.lensHost as? LensController

    private fun on(a: Map<String, String>, current: Boolean): Boolean =
        when (a["s"]) {
            "on", "1", "true" -> true
            "off", "0", "false" -> false
            else -> !current
        }

    private fun exec(cmd: String, a: Map<String, String>): Boolean {
        val arg = a["s"]
        when (cmd) {
            "dump" -> {
                FcLog.i(FcStore.dump())
                ActiveRoom.scene?.let { FcLog.i(it.dump()) }
            }
            "state" -> return jump(a["id"] ?: arg ?: return false)
            "page" -> FcStore.pageSelected(arg ?: return false)
            "filter" -> FcStore.filterChosen(a["id"] ?: arg ?: return false, "debug")
            "hover" -> {
                val id = a["id"] ?: arg ?: return false
                if (id == "off") s().hoverId?.let { FcStore.hover(it, false) } else FcStore.hover(id, true)
            }
            "source" ->
                when (arg) {
                    "sample" -> {
                        FcStore.sourceSelected(Source.SAMPLE)
                        a["photo"]?.let { FcStore.sampleSelected(it) }
                    }
                    "camera" -> FcStore.sourceSelected(Source.CAMERA)
                    "import" -> FcStore.importSampleForDebug(a["photo"] ?: "cat")
                    else -> return false
                }
            "sample" -> FcStore.sampleSelected(arg ?: a["photo"] ?: return false)
            "strength" -> FcStore.strengthChanged((a["n"] ?: arg)?.toFloatOrNull()?.toInt() ?: return false)
            "stamp" -> FcStore.stampToggled(on(a, s().stampOn))
            "stampFormat" -> FcStore.stampFormatSelected(StampFormat.of(arg))
            "compare" -> FcStore.compareToggled(on(a, s().compare))
            "timer" -> FcStore.timerToggled(on(a, s().timerOn))
            "mute" -> FcStore.muteToggled(on(a, s().muted))
            "volume" -> FcStore.volumeChanged((a["n"] ?: arg)?.toFloatOrNull()?.toInt() ?: return false)
            "lang" -> FcStore.langSelected(if (arg == "en") Lang.EN else Lang.ZH)
            "shutter" -> FcStore.shutterPressed("debug")
            "thumb" -> FcStore.albumOpen("thumbnail (debug)")
            "side" ->
                when (arg) {
                    "show" -> FcStore.sideToggled(true)
                    "hide" -> FcStore.sideToggled(false)
                    "more" -> FcStore.moreToggled(true)
                    "less" -> FcStore.moreToggled(false)
                    else -> return false
                }
            "album" -> {
                val items = FcStore.albumItems()
                when (arg) {
                    "open" -> FcStore.albumOpen("debug")
                    "next" -> FcStore.albumStep(1, items.size)
                    "prev" -> FcStore.albumStep(-1, items.size)
                    "delete" -> FcStore.albumDeleteAsk(true)
                    "cancel" -> FcStore.albumDeleteAsk(false)
                    "confirm" -> FcStore.albumDeleteConfirm()
                    "use" -> FcStore.albumUse(items.getOrNull(s().albumIndex)?.uri ?: return false)
                    "dump" -> {
                        val i = s().albumIndex
                        FcLog.i("ALBUM dump page=${s().page} index=$i shows=${items.getOrNull(i)?.name} newest=${items.firstOrNull()?.name} isNewest=${i == 0} count=${items.size}")
                    }
                    else -> return false
                }
            }
            "lens" ->
                when {
                    a["fail"] == "lost" -> lens()?.simulateLost() ?: return false
                    a["fail"] != null -> {
                        lens()?.failNext = a["fail"]
                        if (s().lensError != null) FcStore.lensRetry()
                    }
                    a["pipe"] != null -> lens()?.pipelined = a["pipe"] != "off"
                    arg == "retry" -> FcStore.lensRetry()
                    else -> return false
                }
            "readback" -> FcStore.fakeReadbackMode(a["mode"]?.takeIf { it != "off" } ?: arg?.takeIf { it != "off" })
            // the permission card's states without system dialogs (design qaHooks perm); real ones: pm grant / revoke / set-permission-flags
            "perm" -> perm(a["step"] ?: arg ?: return false)
            // the card's buttons, same calls as the taps
            "permCard" ->
                when (arg) {
                    "start", "grant", "retry" -> FcStore.startPermissions("debug")
                    "settings" -> FcStore.openPermissionSettings()
                    "sample" -> FcStore.sourceSelected(Source.SAMPLE)
                    "ok" -> FcStore.permReviewDone()
                    else -> return false
                }
            "permReview" -> FcStore.permReviewOpen()
            "save" -> FcStore.photos.failNext = a["fail"] ?: arg ?: "io"
            "saveRetry" -> FcStore.saveRetry()
            "import" -> if (a["fail"] != null) {
                FcStore.failNextImport = true
                FcStore.pickPhoto()
            } else FcStore.importSampleForDebug(a["photo"] ?: arg ?: "cat")
            "lut" -> FcStore.lutFromFile(a["path"] ?: arg ?: return false)
            "seed" -> FcStore.seed((a["n"] ?: arg)?.toFloatOrNull()?.toInt())
            "hold" -> FcStore.hold(a["state"] ?: arg)
            "release" -> FcStore.release()
            "room" -> if (arg == "off") FcStore.roomBack("debug") else FcStore.roomTry()
            "roomFilter" -> FcStore.roomStep(if (arg == "prev") -1 else 1, "debug")
            "recenter" -> FcStore.roomRecenter()
            "recall" -> FcStore.recallFront()
            "quit" -> if (arg == "yes") FcStore.quitConfirmed() else FcStore.quitClicked()
            else -> return false
        }
        return true
    }

    /** `perm --es step intro|ask1|ask2|missing|blocked|wait|ok|real`. */
    private fun perm(step: String): Boolean {
        val all = CamPerm.ALL
        val sd = setOf(CamPerm.SPATIAL_DATA)
        fun go(missing: Set<CamPerm>, denied: Boolean = false, blocked: Set<CamPerm> = emptySet(), asking: CamPerm? = null, waiting: Boolean = false) {
            FcStore.fakeMissing = missing
            FcStore.debugSet(s().copy(page = Pages.SHOOT, source = Source.CAMERA, missingPerms = missing, permissionDenied = denied, blockedPerms = blocked, asking = asking, permWaiting = waiting, permReview = false, quitAsked = false), "debug perm $step")
        }
        when (step) {
            "intro" -> go(all)
            "ask1" -> go(all, asking = CamPerm.SPATIAL_DATA)
            "ask2" -> go(setOf(CamPerm.CAMERA), asking = CamPerm.CAMERA)
            "missing" -> go(sd, denied = true)
            "blocked" -> go(sd, denied = true, blocked = sd)
            "wait" -> go(sd, denied = true, blocked = sd, waiting = true)
            "ok" -> {
                FcStore.fakeMissing = emptySet()
                FcStore.permissionRecheck("debug perm ok")
            }
            "real" -> {
                FcStore.fakeMissing = null
                FcStore.permissionRecheck("debug perm real")
            }
            else -> return false
        }
        return true
    }

    /** Design states, each through the same calls as the user's taps; `CMD reached <id>` once there. */
    private fun jump(id: String): Boolean {
        fun reached(want: String, tries: Int = 40) {
            main.postDelayed(object : Runnable {
                var n = 0

                override fun run() {
                    if (s().stateId == want) FcLog.i("CMD reached $want")
                    else if (++n < tries) main.postDelayed(this, 250)
                    else FcLog.w("CMD timeout $want (at ${s().stateId})")
                }
            }, 250)
        }

        /** A clean camera page on the sample with real permissions (the card off). */
        fun base() {
            if (s().room != null) FcStore.roomBack("debug state")
            if (s().quitAsked) FcStore.quitCancelled()
            if (s().lensError != null) FcStore.lensRetry()
            FcStore.fakeMissing = null
            if (FcStore.fakeReadback != null) FcStore.fakeReadbackMode(null)
            FcStore.debugSet(
                s().copy(page = Pages.SHOOT, moreOpen = false, compare = false, hoverId = null, notice = null, timerOn = false, countdown = null, albumDeleteAsk = false, permReview = false, permWaiting = false, asking = null, sideWanted = true),
                "debug base",
            )
            if (s().source != Source.SAMPLE) FcStore.sourceSelected(Source.SAMPLE)
        }

        fun later(ms: Long, f: () -> Unit) = main.postDelayed(f, ms)

        /** Emulator: the sample stands in for the camera frames (read-back FAKE), both permissions faked on. */
        fun fakeCamera(mode: String = "ok") {
            base()
            FcStore.fakeMissing = emptySet()
            FcStore.permissionRecheck("debug fake camera")
            FcStore.fakeReadbackMode(mode)
            FcStore.sourceSelected(Source.CAMERA)
        }
        when (id) {
            "S00" -> perm("intro")
            "S00a" -> perm("ask1")
            "S00b" -> perm("blocked")
            "S00c" -> perm("wait")
            "S01" -> fakeCamera()
            "S01s" -> base()
            "S04" -> {
                base()
                FcStore.hover("F02", true)
            }
            "S05" -> {
                base()
                FcStore.hold("countdown")
                FcStore.timerToggled(true)
                FcStore.shutterPressed("debug S05")
            }
            "S06" -> {
                base()
                FcStore.hold("flash")
                FcStore.shutterPressed("debug S06")
            }
            "S08" -> {
                base()
                FcStore.hold("reading")
                FcStore.shutterPressed("debug S08")
            }
            "S08f" -> {
                fakeCamera("fail")
                later(1_500) { FcStore.shutterPressed("debug S08f") }
                return true.also { FcLog.i("CMD reached S08f (toast after 1.5 s)") }
            }
            "S09" -> {
                base()
                FcStore.importSampleForDebug("cat")
            }
            "S10" -> base()
            "S10h" -> {
                base()
                FcStore.sideToggled(false)
                return true.also { FcLog.i("CMD reached S10h") }
            }
            "S11" -> {
                base()
                val f = java.io.File(FcStore.renderer.lutDir.parentFile, "My Warm Afternoon Look.cube")
                f.writeText(debugCube())
                FcStore.lutFromFile(f.path)
                return true.also { FcLog.i("CMD reached S11") }
            }
            "S12" -> {
                base()
                FcStore.albumOpen("debug S12")
            }
            "S12e" -> {
                base()
                FcStore.albumOpen("debug S12e")
                return true.also { FcLog.i("CMD reached S12e (empty only if there are no photos)") }
            }
            "S13" -> {
                base()
                FcStore.albumOpen("debug S13")
                FcStore.albumDeleteAsk(true)
            }
            "S14" -> {
                base()
                FcStore.roomTry()
            }
            "S15" -> {
                if (s().room == null) {
                    base()
                    FcStore.roomTry()
                    later(4000) { FcStore.roomStep(1, "debug S15") }
                } else FcStore.roomStep(1, "debug S15")
                reached("S14", 60)
                return true
            }
            "S16" -> FcStore.roomBack("debug S16")
            "S17" -> {
                base()
                FcStore.pageSelected(Pages.SETTINGS)
            }
            "S18" -> {
                base()
                FcStore.pageSelected(Pages.SETTINGS)
                FcStore.quitClicked()
            }
            "S19" -> FcStore.langSelected(Lang.EN)
            "S20" -> {
                base()
                FcStore.compareToggled(true)
            }
            "S21" -> {
                fakeCamera()
                FcStore.lensFailed("debug S21")
            }
            "S22" -> {
                base()
                FcStore.fakeMissing = emptySet()
                FcStore.permissionRecheck("debug S22")
                FcStore.sourceSelected(Source.CAMERA)
                return true.also { reached(if (s().isEmulator) "S22" else "S01") }
            }
            "S23" -> {
                base()
                FcStore.muteToggled(true)
                return true.also { FcLog.i("CMD reached S23") }
            }
            "S24" -> {
                fakeCamera()
                later(3000) { FcStore.windowStopped() }
                later(5000) { FcStore.windowInFront("debug S24") }
                return true.also { FcLog.i("CMD reached S24 (background at 3 s, back at 5 s)") }
            }
            "S25" -> {
                base()
                FcStore.hold("lost")
                FcStore.sourceSelected(Source.CAMERA)
                later(2000) { if (lens()?.isOpen == true) lens()?.simulateLost() else FcStore.lensLost("debug S25") }
            }
            "S26" -> {
                base()
                FcStore.photos.failNext = "io"
                FcStore.shutterPressed("debug S26")
                return true.also { FcLog.i("CMD reached S26 (red dot + toast)") }
            }
            "S27" -> {
                base()
                FcStore.failNextImport = true
                FcStore.importFrom(android.net.Uri.parse("content://media/external/images/media/0"), "broken.heic")
                return true.also { FcLog.i("CMD reached S27") }
            }
            "S28" -> {
                base()
                FcStore.filterChosen("F10", "debug S28")
                return true.also { FcLog.i("CMD reached S28") }
            }
            "S29" -> {
                base()
                FcStore.moreToggled(true)
            }
            else -> return false
        }
        reached(id, 60)
        return true
    }

    /** A small warm .cube (17 grid) for the LUT import state S11. */
    private fun debugCube(): String {
        val n = 17
        val sb = StringBuilder("TITLE \"My Warm Afternoon Look\"\nLUT_3D_SIZE $n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val rr = (r / (n - 1f) * 1.08f + 0.03f).coerceAtMost(1f)
            val gg = g / (n - 1f) * 1.0f + 0.01f
            val bb = b / (n - 1f) * 0.82f
            sb.append(String.format(java.util.Locale.US, "%.5f %.5f %.5f\n", rr, gg.coerceAtMost(1f), bb))
        }
        return sb.toString()
    }
}
