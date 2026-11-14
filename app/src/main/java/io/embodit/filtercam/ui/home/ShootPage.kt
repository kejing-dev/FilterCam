package io.embodit.filtercam.ui.home

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.NoticeKind
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.app.Source
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The camera page (design round 3, QA #2 q2-2 / profile B31: one visual lead, one main button, 7 controls with the
 * 3 tabs). Main screen, ≈ 604 dp: the preview 560 x 420, the filter row ‹ name › (64 dp), the bottom row 「更多」 · shutter ·
 * mute (mute always shown, B31 exception). 「更多」 (S29) keeps the preview at 320 x 240 on top and puts the drawer under
 * it in the same window: source, sample photo, strength, date stamp + timer, original, viewfinder, room mode, all filters.
 * Notices are a 3 s line over the preview's lower edge, not a row of their own.
 */
@Composable
fun ShootPage(state: FcState) {
    val t = LocalTxt.current
    if (state.moreOpen) MoreDrawer(state, t) else MainScreen(state, t)
}

@Composable
private fun MainScreen(state: FcState, t: Txt) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Preview(state, t, 560.dp, 420.dp)
        Spacer(Modifier.height(12.dp))
        FilterRow(state, t)
        Spacer(Modifier.height(12.dp))
        BottomRow(state, t)
    }
}

// ---------------------------------------------------------------------------------------------
// the preview (the page's lead) and its 3 s notice line
// ---------------------------------------------------------------------------------------------

/**
 * The current source through the current filter (Compose Image); with the camera the filter's sample and a corner line
 * 「画面在取景窗里 →」 (H3). White flash on the shutter (S06), the countdown on the shutter (S05).
 */
@Composable
private fun Preview(state: FcState, t: Txt, w: Dp, h: Dp) {
    val preview by FcStore.renderer.preview.collectAsStateWithLifecycle()
    val square = FcStore.def(state.previewFilter).square
    Box(Modifier.size(w, h).clip(RoundedCornerShape(20.dp)).background(FcColors.Plate), contentAlignment = Alignment.Center) {
        val bmp = preview
        if (state.source == Source.CAMERA && !state.permissionGranted && state.hoverId == null) {
            CameraPermission(state, t, big = w >= 400.dp)
        } else if (state.noCamera && state.hoverId == null) {
            FitText(t(R.string.no_camera), where = "shoot/noCamera", style = FcType.body, color = FcColors.OnPlate, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(24.dp))
        } else if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = if (square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (state.source == Source.CAMERA && state.hoverId == null) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).background(FcColors.Plate.copy(alpha = 0.82f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    FitText(t(R.string.camera_in_lens), where = "shoot/inLens", style = FcType.small, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
                }
            }
        }
        if (state.flashing) Box(Modifier.fillMaxSize().background(FcColors.Flash.copy(alpha = 0.85f)))
        Toast(state, t, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Camera source without CAMERA (B19, 2026-11-14 headset): where the picture goes, say what is missing and offer the way
 * on: 「授权」 (the system dialog) before any answer; after a refusal 「去设置」 (the app's settings page) and, while the
 * system will still ask, 「再问一次」. Never a black box.
 */
@Composable
private fun CameraPermission(state: FcState, t: Txt, big: Boolean) {
    val keyH = if (big) 64.dp else 48.dp
    // Settings opens beside this window in Shared Space, so the window is not paused and gets no ON_RESUME on the way
    // back: while this card shows, look again every 1.5 s and drop it once CAMERA is on
    LaunchedEffect(Unit) {
        while (true) {
            delay(PERMISSION_POLL_MS)
            FcStore.permissionRecheck("card poll")
        }
    }
    Column(Modifier.fillMaxSize().padding(if (big) 32.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painter = painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(if (big) 48.dp else 28.dp), tint = FcColors.OnPlate)
        Spacer(Modifier.height(if (big) 16.dp else 8.dp))
        FitText(
            t(if (state.permissionDenied) R.string.cam_perm_denied else R.string.cam_perm_need),
            where = "shoot/camPerm",
            style = if (big) FcType.body else FcType.small,
            color = FcColors.OnPlate,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(if (big) 20.dp else 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.permissionDenied) {
                PillButton(t(R.string.cam_perm_grant), "shoot/camPermGrant", accent = true, modifier = Modifier.height(keyH)) { FcStore.askCamera("preview button") }
            } else {
                PillButton(t(R.string.cam_perm_settings), "shoot/camPermSettings", accent = true, modifier = Modifier.height(keyH)) { FcStore.openPermissionSettings() }
                if (!state.permissionBlocked) PillButton(t(R.string.cam_perm_retry), "shoot/camPermRetry", onPlate = true, modifier = Modifier.height(keyH)) { FcStore.askCamera("ask again") }
            }
        }
    }
}

/** The one-line notices (design S16 / S24 / S25 / S27 / S28 / S11 3 s; the lens note once, 4 s; lens reopening / closing while it lasts). */
@Composable
private fun Toast(state: FcState, t: Txt, modifier: Modifier) {
    val n = state.notice
    LaunchedEffect(n?.serial) {
        if (n == null) return@LaunchedEffect
        delay(NOTICE_MS)
        if (FcStore.state.value.notice?.serial == n.serial) FcStore.noticeDismissed()
    }
    var showLensNote by remember { mutableStateOf(false) }
    LaunchedEffect(state.lens.live) {
        if (!state.lens.live || lensNoteShown) return@LaunchedEffect
        lensNoteShown = true
        showLensNote = true
        delay(LENS_NOTE_MS)
        showLensNote = false
    }
    val text =
        when {
            state.lens.reopening -> t(R.string.lens_reopen)
            state.lens.closing -> t(R.string.closing_lens)
            n != null ->
                when (n.kind) {
                    NoticeKind.LENS_CLOSED_SAMPLE -> t(R.string.lens_closed_sample)
                    NoticeKind.RESUME -> t(R.string.resume_hint)
                    NoticeKind.LENS_LOST -> t(R.string.lens_lost)
                    NoticeKind.V11 -> t(R.string.v11_soon, FilterText.name(t, n.arg ?: "F10"))
                    NoticeKind.IMPORT_FAILED -> t(R.string.import_failed)
                    NoticeKind.LUT_BAD -> t(R.string.lut_bad)
                    NoticeKind.LUT_ADDED -> t(R.string.lut_added, Filters.shortLutName(n.arg ?: ""))
                }
            showLensNote -> t(R.string.lens_note)
            else -> null
        }
    AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().background(FcColors.Plate.copy(alpha = 0.88f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            FitText(text ?: "", where = "shoot/toast", style = FcType.small, color = FcColors.OnPlate, maxLines = 2)
        }
    }
}

private var lensNoteShown = false
private const val NOTICE_MS = 3_000L
private const val LENS_NOTE_MS = 4_000L
private const val TAGLINE_MS = 2_000L
private const val PERMISSION_POLL_MS = 1_500L

// ---------------------------------------------------------------------------------------------
// main screen rows
// ---------------------------------------------------------------------------------------------

/** ‹ name › (64 dp): the 8 v1 filters plus imported LUTs round and round; the tagline shows for 2 s after a change. */
@Composable
private fun FilterRow(state: FcState, t: Txt) {
    var showTagline by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(state.filterId) {
        if (first) return@LaunchedEffect run { first = false }
        showTagline = true
        delay(TAGLINE_MS)
        showTagline = false
    }
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundKey(R.drawable.ic_chevron_left, t(R.string.prev_filter), "shoot/prev") { FcStore.filterStep(-1) }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(FilterText.name(t, state.filterId), where = "shoot/filterName", style = FcType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, softWrap = false)
            val tagline = if (state.filterId.startsWith(FilterDef.LUT_PREFIX)) "" else FilterText.tagline(t, state.filterId)
            AnimatedVisibility(showTagline && tagline.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                FitText(tagline, where = "shoot/tagline", style = FcType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, softWrap = false)
            }
        }
        RoundKey(R.drawable.ic_chevron_right, t(R.string.next_filter), "shoot/next") { FcStore.filterStep(1) }
    }
}

/** A 64 dp round key with an icon only; [label] is its accessibility name. */
@Composable
private fun RoundKey(icon: Int, label: String, where: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(64.dp)
            .clip(CircleShape)
            .background(PicoTheme.colorScheme.fillTertiary)
            .spatialHoverEffect()
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClickLabel = label, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

/** 「更多」 · shutter · mute (96 dp, 16 dp apart; mute stays on the main screen, B31 exception). */
@Composable
private fun BottomRow(state: FcState, t: Txt) {
    Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BigPill(t(R.string.more), "shoot/more", R.drawable.ic_more) { FcStore.moreToggled(true) }
        }
        Shutter(state, t)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            BigPill(if (state.muted) t(R.string.unmute) else t(R.string.mute), "shoot/mute", if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on) { FcStore.muteToggled() }
        }
    }
}

/** A 64 dp high secondary button with icon and word (≥ 120 dp wide, design 「更多」). */
@Composable
private fun BigPill(label: String, where: String, icon: Int, enabled: Boolean = true, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = if (accent) FcColors.OnAccent else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp).widthIn(min = 140.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) FcColors.Accent else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
        Spacer(Modifier.width(8.dp))
        FitText(label, where = where, maxLines = 1, softWrap = false, color = ink)
    }
}

/** Design: Ø 88 dp accent disc, dark word, white ring; the countdown number replaces the word (S05), a spinner while reading (S08). */
@Composable
private fun Shutter(state: FcState, t: Txt) {
    val enabled = FcStore.shutterEnabled || state.countdown != null
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(88.dp)
            .border(2.dp, FcColors.ShutterRing.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) FcColors.Accent else PicoTheme.colorScheme.fillTertiary)
            .spatialHoverEffect()
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled) { FcStore.shutterPressed() }
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        val word =
            when {
                state.countdown != null -> "${state.countdown}"
                state.reading -> t(R.string.reading)
                else -> t(R.string.shutter)
            }
        FitText(
            word,
            where = "shoot/shutter",
            style = if (state.countdown != null) FcType.bigClock else if (state.reading) FcType.small else FcType.cardName,
            color = if (enabled) FcColors.OnAccent else PicoTheme.colorScheme.labelPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 「更多」 (S29): the preview stays on top at 320 x 240, the drawer scrolls under it
// ---------------------------------------------------------------------------------------------

@Composable
private fun MoreDrawer(state: FcState, t: Txt) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Box(Modifier.align(Alignment.Center)) { Preview(state, t, 320.dp, 240.dp) }
            BigPill(t(R.string.more_done), "more/done", R.drawable.ic_more, modifier = Modifier.align(Alignment.TopEnd)) { FcStore.moreToggled(false) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            SourceRow(state, t)
            SampleRow(state, t)
            StrengthRow(state, t)
            StampTimerRow(state, t)
            CompareRow(state, t)
            LensRow(state, t)
            ActionRow { BigPill(t(R.string.room_try), "more/room", R.drawable.ic_room) { FcStore.roomTry() } }
            ActionRow { BigPill(t(R.string.all_filters) + " →", "more/filters", R.drawable.ic_filters) { FcStore.pageSelected(Pages.FILTERS) } }
        }
    }
}

private val ROW_H = 56.dp

@Composable
private fun Label(text: String, where: String, modifier: Modifier = Modifier) =
    FitText(text, where = where, style = FcType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, softWrap = false, modifier = modifier)

@Composable
private fun SourceRow(state: FcState, t: Txt) {
    Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
        Label(t(R.string.shoot_from), "more/source", Modifier.weight(1f))
        Options(
            listOf(Source.CAMERA to t(R.string.src_camera), Source.SAMPLE to t(R.string.src_sample), Source.IMPORT to t(R.string.src_import)),
            state.source,
            112.dp,
            "more/source",
        ) { FcStore.sourceSelected(it) }
    }
}

/** Which sample photo (tap › for the next of 6); with 「我的照片」 the file name (cut to 14, M8) and 「选一张」; hidden with the camera. */
@Composable
private fun SampleRow(state: FcState, t: Txt) {
    if (state.source == Source.CAMERA) return
    Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
        if (state.source == Source.IMPORT) {
            Label(Filters.shortLutName(state.importName ?: ""), "more/importName", Modifier.weight(1f))
            PillButton(t(R.string.pick_photo), "more/pick") { FcStore.pickPhoto() }
        } else {
            Label(t(R.string.sample_photo), "more/sample", Modifier.weight(1f))
            val i = FilterText.samples.indexOf(state.sample).coerceAtLeast(0)
            PillButton(FilterText.sample(t, state.sample) + "  ›", "more/sampleNext") { FcStore.sampleSelected(FilterText.samples[Math.floorMod(i + 1, FilterText.samples.size)]) }
        }
    }
}

@Composable
private fun StrengthRow(state: FcState, t: Txt) {
    Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
        Label(t(R.string.strength), "more/strength", Modifier.width(120.dp))
        // a local Float follows the finger (B20); the store gets whole steps as it moves
        var k by remember(state.strength) { mutableFloatStateOf(state.strength.toFloat()) }
        Slider(
            value = k,
            onValueChange = {
                k = it
                FcStore.strengthChanged(it.roundToInt())
            },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..100f,
            enabled = !state.compare,
            colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = FcColors.Accent, progressHighColor = FcColors.Accent),
        )
        Spacer(Modifier.width(16.dp))
        Label("${state.strength}", "more/strength", Modifier.width(44.dp))
    }
}

/** Date stamp and the 3 s timer on one row, each with its word (design). */
@Composable
private fun StampTimerRow(state: FcState, t: Txt) {
    Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
        SwitchCell(t(R.string.stamp), "more/stamp", state.stampOn, Modifier.weight(1f)) { FcStore.stampToggled(it) }
        Spacer(Modifier.width(24.dp))
        SwitchCell(t(R.string.timer), "more/timer", state.timerOn, Modifier.weight(1f)) { FcStore.timerToggled(it) }
    }
}

@Composable
private fun SwitchCell(label: String, where: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier.height(ROW_H).clickable(enabled = enabled) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Label(label, where, Modifier.weight(1f))
        Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) { Switch(checked = on, enabled = enabled, onCheckedChange = onChange) }
    }
}

/** 「原样 开 / 关」 (D19: the button says what it shows now). */
@Composable
private fun CompareRow(state: FcState, t: Txt) {
    ActionRow {
        BigPill(if (state.compare) t(R.string.compare_on) else t(R.string.compare_off), "more/compare", R.drawable.ic_compare, accent = state.compare) { FcStore.compareToggled() }
    }
}

/** The viewfinder on / off (with the camera, off = back to the sample, R2-2); busy while it opens, closes or reopens. */
@Composable
private fun LensRow(state: FcState, t: Txt) {
    val busy = state.lens.closing || state.lens.reopening
    val label =
        when {
            state.lens.closing -> t(R.string.closing_lens)
            state.lens.opening -> t(R.string.opening_lens)
            else -> t(R.string.viewfinder)
        }
    SwitchCell(label, "more/lens", state.lens.open, Modifier.fillMaxWidth(), enabled = !busy) { on ->
        if (on) FcStore.openLens("more") else FcStore.closeLens("more", toSample = true)
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(ROW_H + 8.dp), contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
fun Badge(text: String, modifier: Modifier) {
    Box(modifier.background(FcColors.Accent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        FitText(text, where = "badge", style = FcType.tiny, color = FcColors.OnAccent, maxLines = 1)
    }
}
