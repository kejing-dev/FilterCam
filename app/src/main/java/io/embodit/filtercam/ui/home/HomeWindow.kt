package io.embodit.filtercam.ui.home

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.filtercam.BuildConfig
import io.embodit.filtercam.HOME_AGAIN_ID
import io.embodit.filtercam.HOME_WINDOW_ID
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.Lang
import io.embodit.filtercam.app.Overlay
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.domain.StampFormat
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.ui.room.ROOM_STAGE_ID
import io.embodit.filtercam.ui.shot.ShotCardView
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.window.ExitRow
import io.embodit.filtercam.ui.window.MainWindow
import io.embodit.filtercam.ui.window.MainWindowPage
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main window P1 (design: Shared Space, 760 x 680 dp narrowed to 680 x 680 dp for QA #1 q1-3, system glass): the app's one control window, built on the
 * factory main window template. Pages on the native TabBar: 取景 (camera page), 滤镜 (filter gallery P7), 相册 (P8)
 * and the template's settings (P9, quit last, D26). The tips / permission card (P6) and the "viewfinder can't open"
 * card (S21) and the shot card (P4) are drawn over the page inside this window. The system window bar under it moves and closes it; nothing
 * here drags (profile D21).
 */
@Composable
fun HomeWindow(windowId: String = HOME_WINDOW_ID, windowTag: String? = null) {
    val state by FcStore.state.collectAsStateWithLifecycle()
    val t = LocalTxt.current
    WindowBridge(windowId, windowTag)
    val pages =
        listOf(
            MainWindowPage(Pages.SHOOT, t(R.string.tab_shoot), R.drawable.ic_camera, showTitle = false) { ShootPage(state) },
            MainWindowPage(Pages.GALLERY, t(R.string.tab_gallery), R.drawable.ic_gallery, showTitle = false) { GalleryPage(state) },
            // design round 3: 「滤镜」 left the TabBar, it is 「更多 → 所有滤镜」 now (back to the drawer from its title row)
            MainWindowPage(Pages.FILTERS, t(R.string.all_filters), R.drawable.ic_filters, showTitle = false, inTabBar = false) { FiltersPage(state) },
        )
    Box(Modifier.fillMaxSize()) {
        MainWindow(
            pages = pages,
            current = state.page,
            onSelect = { FcStore.pageSelected(it) },
            settingsIcon = R.drawable.ic_settings,
            settingsLabel = t(R.string.tab_settings),
            settings = { SettingsRows(state) },
            exit =
                ExitRow(t(R.string.quit_app), t(R.string.quit_ask), t(R.string.quit_no), t(R.string.quit_yes), asked = state.quitAsked, recall = t(R.string.recall_front), onRecall = { FcStore.recallFront() }) {
                    if (it) FcStore.quitClicked() else FcStore.quitCancelled()
                },
            exitColor = FcColors.Danger,
            onExitColor = FcColors.OnDanger,
            onExit = { FcStore.quitConfirmed() },
        )
        when (state.overlay) {
            Overlay.GUIDE -> GuideCard(Modifier.align(Alignment.Center))
            Overlay.PERMISSION -> PermissionCard(Modifier.align(Alignment.Center))
            null -> state.card?.let { ShotCardView(it, Modifier.align(Alignment.Center)) } ?: state.lensError?.let { LensErrorCard(it, Modifier.align(Alignment.Center)) }
        }
    }
}

/**
 * Lends the store this window's navigator (lens spot, room Stage), reports the window's lifecycle (S24:
 * the viewfinder goes with the background) and steps the window aside while room mode is on (a Shared Space window
 * draws over the Stage; the Stage opens it again on the way out, only with a level head, profile D9).
 */
@Composable
private fun WindowBridge(windowId: String, windowTag: String?) {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by FcStore.state.collectAsStateWithLifecycle()
    DisposableEffect(navigator, lifecycleOwner) {
        val host =
            object : FcStore.WindowHost {
                override fun openSpot(gen: Int) = navigator.openWindowContainer(LENS_SPOT_ID, "spot$gen")

                override fun closeSpot() {
                    runCatching { navigator.closeWindowContainer(LENS_SPOT_ID) }.onFailure { FcLog.w("LENS spot close: $it") }
                }

                override fun openStage() {
                    scope.launch(Dispatchers.Main.immediate) {
                        val result = runCatching { navigator.openStage(ROOM_STAGE_ID, StageStyle.Mixed) }
                        when (val r = result.getOrNull()) {
                            is OpenStageResult.Allowed -> FcStore.stageResult(true, "Allowed")
                            is OpenStageResult.NotAllowed -> FcStore.stageResult(false, "NotAllowed")
                            is OpenStageResult.Error -> FcStore.stageResult(false, "Error ${r.code} ${r.reason}")
                            null -> FcStore.stageResult(false, "threw ${result.exceptionOrNull()}")
                        }
                    }
                }

                override fun closeStage() {
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() }.onFailure { FcLog.w("STAGE close failed: $it") } }
                }

                override fun recallFront() {
                    // a new instance (new tag) of the same main window, placed where the system opens new windows; then this one goes
                    val tag = "home${SystemClock.elapsedRealtime() % 1_000_000}"
                    val opened = runCatching { navigator.openWindowContainer(HOME_AGAIN_ID, tag) }
                    FcLog.i("WINDOW recall: open $HOME_AGAIN_ID/$tag ${opened.exceptionOrNull() ?: "ok"}")
                    if (opened.isSuccess) runCatching { navigator.closeWindowContainer() }.onFailure { FcLog.w("WINDOW recall: close old failed: $it") }
                }
            }
        FcStore.windowHost = host
        FcStore.windowReady()
        val observer =
            LifecycleEventObserver { _, event ->
                if (!FcStore.recalling) when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        FcStore.permissionRecheck("window resumed")
                        FcStore.windowResumed()
                    } // also polled by the permission card (Settings opens beside, no resume)
                    Lifecycle.Event.ON_STOP -> if (FcStore.hiddenHome == null && FcStore.state.value.room == null) FcStore.appStopped()
                    Lifecycle.Event.ON_DESTROY ->
                        if (FcStore.hiddenHome == null && FcStore.state.value.room == null) {
                            // closed with the window bar's ×: the viewfinder goes with it
                            FcStore.closeLens("main window closed", toSample = true)
                        }
                    else -> Unit
                }
                FcStore.homeLife.value = event.name
                FcLog.i("WINDOW $windowId/$windowTag ${event.name}")
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // after 「叫回面前」 the new window has already lent its host: only clear our own
            if (FcStore.windowHost === host) FcStore.windowHost = null
        }
    }
    // room mode: the window closes itself once the Stage is up; the Stage reopens it (RoomScreen)
    val inRoom = state.room != null
    LaunchedEffect(inRoom) {
        if (!inRoom) return@LaunchedEffect
        delay(STEP_ASIDE_MS)
        if (FcStore.state.value.room == null) return@LaunchedEffect
        FcStore.hiddenHome = windowId to windowTag
        val ok = runCatching { navigator.closeWindowContainer() }
        FcLog.i("WINDOW steps aside for room mode: ${ok.exceptionOrNull() ?: "ok"}")
        if (ok.isFailure) FcStore.hiddenHome = null
    }
}

private const val STEP_ASIDE_MS = 1_200L

// ---------------------------------------------------------------------------------------------
// settings (P9)
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsRows(state: FcState) {
    val t = LocalTxt.current
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.lang), where = "settings/lang", style = FcType.body, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
        Options(listOf(Lang.ZH to t(R.string.lang_zh), Lang.EN to t(R.string.lang_en)), state.lang, 120.dp, "settings/lang") { FcStore.langSelected(it) }
    }
    Row(Modifier.fillMaxWidth().height(56.dp).clickable { FcStore.muteToggled() }, verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.mute), where = "settings/mute", style = FcType.body, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
        Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) { Switch(checked = state.muted, onCheckedChange = { FcStore.muteToggled(it) }) }
    }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.volume), where = "settings/volume", style = FcType.body, color = ink, modifier = Modifier.width(140.dp), maxLines = 1)
        // a local Float follows the finger; the step is taken on release (B20: an Int-bound slider swallows taps and small drags)
        var vol by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
        Slider(
            value = vol,
            onValueChange = { vol = it },
            onValueChangeFinished = { FcStore.volumeChanged(vol.roundToInt()) },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..2f,
            enabled = !state.muted,
            colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = FcColors.Accent, progressHighColor = FcColors.Accent),
        )
        Spacer(Modifier.width(12.dp))
        FitText(t(listOf(R.string.vol_low, R.string.vol_mid, R.string.vol_high)[state.volume]), where = "settings/volume", style = FcType.body, color = ink, modifier = Modifier.width(48.dp), maxLines = 1)
    }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.stamp_format), where = "settings/stamp", style = FcType.body, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
        Options(
            listOf(StampFormat.SHORT to (FcStore.stampTextFor(StampFormat.SHORT) ?: ""), StampFormat.DOTTED to (FcStore.stampTextFor(StampFormat.DOTTED) ?: ""), StampFormat.OFF to t(R.string.fmt_off)),
            state.stampFormat,
            128.dp,
            "settings/stamp",
        ) { FcStore.stampFormatSelected(it) }
    }
    Row(Modifier.fillMaxWidth().height(56.dp).clickable { FcStore.saveRawToggled() }, verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.save_raw), where = "settings/raw", style = FcType.body, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
        Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) { Switch(checked = state.saveRaw, onCheckedChange = { FcStore.saveRawToggled(it) }) }
    }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.save_where), where = "settings/where", style = FcType.body, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
        PillButton(t(R.string.guide_again), "settings/guide") { FcStore.guideAgain() }
    }
    // 「怎么操作」 (B19, 2026-11-14): one line per main action, gesture and controller
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FitText(t(R.string.how_to_title), where = "settings/howTo", style = FcType.cardName, color = ink, maxLines = 1)
        listOf(
            R.drawable.ic_chevron_right to R.string.how_to_filter,
            R.drawable.ic_camera to R.string.how_to_shoot,
            R.drawable.ic_more to R.string.how_to_more,
            R.drawable.ic_room to R.string.how_to_room,
            R.drawable.ic_recenter to R.string.how_to_window,
        ).forEach { (icon, line) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                Spacer(Modifier.width(10.dp))
                FitText(t(line), where = "settings/howTo", style = FcType.small, color = ink, maxLines = 2)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        FitText(t(R.string.about), where = "settings/about", style = FcType.cardName, color = ink, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        FitText(t(R.string.about_text, "v" + BuildConfig.VERSION_NAME), where = "settings/about", style = FcType.small, color = ink, maxLines = 6)
    }
}

// ---------------------------------------------------------------------------------------------
// cards over the page (P6, S21)
// ---------------------------------------------------------------------------------------------

@Composable
private fun GuideCard(modifier: Modifier) {
    val t = LocalTxt.current
    OverlayCard(modifier, 520.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FitText(t(R.string.app_name), where = "guide/title", style = FcType.title, color = FcColors.OnPlate, maxLines = 1)
            listOf(R.drawable.ic_filters to R.string.guide_1, R.drawable.ic_camera to R.string.guide_2, R.drawable.ic_more to R.string.guide_3).forEach { (icon, line) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(FcColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = FcColors.OnAccent)
                    }
                    Spacer(Modifier.width(14.dp))
                    FitText(t(line), where = "guide/line", style = FcType.body, color = FcColors.OnPlate, maxLines = 2)
                }
            }
            Button(onClick = { FcStore.guideOk() }, modifier = Modifier.fillMaxWidth().height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = FcColors.Accent, contentColor = FcColors.OnAccent)) {
                Text(t(R.string.got_it), color = FcColors.OnAccent)
            }
        }
    }
}

@Composable
private fun PermissionCard(modifier: Modifier) {
    val t = LocalTxt.current
    OverlayCard(modifier, 520.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FitText(t(R.string.permission_ask), where = "permission/text", style = FcType.body, color = FcColors.OnPlate, maxLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { FcStore.permissionNotNow() }, modifier = Modifier.weight(1f).height(64.dp)) { FitText(t(R.string.not_now), where = "permission/no", maxLines = 1) }
                Button(onClick = { FcStore.permissionAllow() }, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = FcColors.Accent, contentColor = FcColors.OnAccent)) {
                    FitText(t(R.string.go_grant), where = "permission/yes", maxLines = 1, color = FcColors.OnAccent)
                }
            }
        }
    }
}

@Composable
private fun LensErrorCard(reason: String, modifier: Modifier) {
    val t = LocalTxt.current
    OverlayCard(modifier, 480.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(FcColors.Danger, CircleShape))
                Spacer(Modifier.width(10.dp))
                FitText(t(R.string.lens_unavailable, ""), where = "lensError/title", style = FcType.cardName, color = FcColors.OnPlate, maxLines = 2)
            }
            // a plain line, not the technical reason (QA #3 known issue: "SpatialML not ready after 8 s" on the card); the reason is in the log
            FitText(t(R.string.lens_fail_hint), where = "lensError/reason", style = FcType.small, color = FcColors.OnPlate, maxLines = 3)
            Button(onClick = { FcStore.lensErrorOk() }, modifier = Modifier.height(64.dp).width(140.dp), colors = ButtonDefaults.buttonColors(containerColor = FcColors.Accent, contentColor = FcColors.OnAccent)) {
                Text(t(R.string.ok), color = FcColors.OnAccent)
            }
        }
    }
}
