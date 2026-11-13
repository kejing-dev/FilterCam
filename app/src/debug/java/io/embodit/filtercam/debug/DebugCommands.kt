package io.embodit.filtercam.debug

import android.os.Handler
import android.os.Looper
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.Lang
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.app.Source
import io.embodit.filtercam.domain.StampFormat
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.LensController
import io.embodit.filtercam.ui.room.ActiveRoom

/**
 * Debug commands (design qaHooks). Every command goes through the same store functions as the buttons and logs
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
            "step" -> FcStore.filterStep(if (arg == "prev") -1 else 1, "debug")
            "more" -> FcStore.moreToggled(arg != "close" && arg != "off")
            "recall" -> FcStore.recallFront()
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
            "guide" -> if (arg == "reset") FcStore.guideAgain() else FcStore.guideOk()
            "shutter" -> FcStore.shutterPressed("debug")
            "lensButton" -> FcStore.lensButton()
            "lens" ->
                when {
                    a["fail"] == "lost" -> lens()?.simulateLost() ?: return false
                    a["fail"] != null -> {
                        lens()?.failNext = a["fail"]
                        if (!s().lens.open) FcStore.openLens("debug fail ${a["fail"]}")
                    }
                    arg == "open" -> FcStore.openLens("debug")
                    arg == "close" -> FcStore.closeLens("debug", toSample = true)
                    else -> return false
                }
            "readback" -> {
                FcStore.fakeReadback = a["mode"]?.takeIf { it != "off" } ?: arg?.takeIf { it != "off" }
                if (FcStore.fakeReadback != null) FcStore.debugSet(s().copy(permissionGranted = false, permissionDenied = false), "debug readback ${FcStore.fakeReadback}")
            }
            "permission" -> FcStore.permissionAllow()
            "notNow" -> FcStore.permissionNotNow()
            "save" -> FcStore.photos.failNext = a["fail"] ?: arg ?: "io"
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
            "card" ->
                when (arg) {
                    "again" -> FcStore.cardAgain()
                    "ok" -> FcStore.cardOk()
                    "delete" -> FcStore.cardDeleteAsk(true)
                    "deleteYes" -> FcStore.cardDeleteConfirm()
                    "deleteNo" -> FcStore.cardDeleteAsk(false)
                    "redo" -> FcStore.cardRedo()
                    "retry" -> FcStore.cardRetry()
                    else -> return false
                }
            "gallery" -> {
                val i = (a["i"] ?: arg)?.toFloatOrNull()?.toInt() ?: 0
                val items = FcStore.photos.query()
                val it = items.getOrNull(i) ?: return false
                FcStore.pageSelected(Pages.GALLERY)
                FcStore.galleryOpen(it.uri, it.raw)
            }
            "quit" -> if (arg == "yes") FcStore.quitConfirmed() else FcStore.quitClicked()
            "lensError" -> FcStore.lensErrorOk()
            else -> return false
        }
        return true
    }

    /** Design states S00 – S28, each through the same calls as the user's taps; `CMD reached <id>` once there. */
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

        fun base() {
            if (s().room != null) FcStore.roomBack("debug state")
            if (s().card != null) FcStore.cardOk()
            if (s().overlay != null) FcStore.debugSet(s().copy(overlay = null), "debug clear overlay")
            if (s().quitAsked) FcStore.quitCancelled()
            if (s().lensError != null) FcStore.lensErrorOk()
            FcStore.fakeReadback = null
            FcStore.debugSet(s().copy(page = Pages.SHOOT, moreOpen = false, compare = false, hoverId = null, notice = null, timerOn = false, countdown = null), "debug base")
            if (s().source != Source.SAMPLE) FcStore.sourceSelected(Source.SAMPLE)
        }

        fun later(ms: Long, f: () -> Unit) = main.postDelayed(f, ms)

        fun camShutter(mode: String) {
            base()
            FcStore.fakeReadback = mode
            FcStore.debugSet(s().copy(permissionGranted = false, permissionDenied = false), "debug fake camera $mode")
            FcStore.sourceSelected(Source.CAMERA)
        }
        when (id) {
            "S00" -> FcStore.guideAgain()
            "S01" -> {
                base()
                if (s().lens.open) FcStore.closeLens("debug S01", toSample = true)
            }
            "S02" -> {
                base()
                FcStore.openLens("debug S02")
            }
            "S03", "S22" -> {
                base()
                FcStore.sourceSelected(Source.CAMERA)
                return true.also { reached(if (s().isEmulator) "S22" else "S03") }
            }
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
                FcStore.shutterPressed("debug S06")
                reached("S06", 60)
                return true
            }
            "S07" -> {
                camShutter("ok")
                FcStore.debugSet(s().copy(permissionGranted = false), "debug no permission")
                later(1500) { FcStore.shutterPressed("debug S07") }
            }
            "S07r" -> {
                camShutter("sessionLevel")
                FcStore.debugSet(s().copy(permissionGranted = false), "debug no permission")
                FcStore.hold("reopen")
                later(1500) {
                    FcStore.shutterPressed("debug S07r")
                    later(500) { FcStore.permissionAllow() }
                }
            }
            "S08" -> {
                camShutter("ok")
                FcStore.debugSet(s().copy(permissionGranted = true), "debug permission")
                FcStore.hold("reading")
                later(1500) { FcStore.shutterPressed("debug S08") }
            }
            "S08n" -> {
                camShutter("denied")
                later(1500) { FcStore.shutterPressed("debug S08n") }
            }
            "S08f" -> {
                camShutter("fail")
                FcStore.debugSet(s().copy(permissionGranted = true), "debug permission")
                later(1500) { FcStore.shutterPressed("debug S08f") }
            }
            "S09" -> {
                base()
                FcStore.importSampleForDebug("cat")
            }
            "S10" -> {
                base()
                FcStore.pageSelected(Pages.FILTERS)
            }
            "S29" -> {
                base()
                FcStore.moreToggled(true)
            }
            "S11" -> {
                base()
                FcStore.pageSelected(Pages.FILTERS)
                val f = java.io.File(FcStore.renderer.lutDir.parentFile, "My Warm Afternoon Look.cube")
                f.writeText(debugCube())
                FcStore.lutFromFile(f.path)
                reached("S10")
                return true
            }
            "S12" -> {
                base()
                FcStore.pageSelected(Pages.GALLERY)
            }
            "S13" -> {
                base()
                val first = FcStore.photos.query().firstOrNull() ?: return false
                FcStore.pageSelected(Pages.GALLERY)
                FcStore.galleryOpen(first.uri, first.raw)
                FcStore.cardDeleteAsk(true)
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
                base()
                if (s().lens.open) FcStore.closeLens("debug S21", toSample = true)
                lens()?.failNext = "init"
                FcStore.openLens("debug S21")
            }
            "S23" -> {
                base()
                FcStore.muteToggled(true)
                return true.also { FcLog.i("CMD reached S23") }
            }
            "S24" -> {
                base()
                FcStore.openLens("debug S24")
                later(5000) { FcStore.appStopped() }
                return true.also { FcLog.i("CMD reached S24 (after 5 s)") }
            }
            "S25" -> {
                base()
                FcStore.openLens("debug S25")
                later(5000) { lens()?.simulateLost() }
                return true.also { FcLog.i("CMD reached S25 (after 5 s)") }
            }
            "S26" -> {
                base()
                FcStore.photos.failNext = "io"
                FcStore.shutterPressed("debug S26")
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
