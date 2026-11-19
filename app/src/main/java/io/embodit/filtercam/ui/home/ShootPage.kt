package io.embodit.filtercam.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.NoticeKind
import io.embodit.filtercam.app.Source
import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.domain.PermCard
import io.embodit.filtercam.domain.PermChip
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The 「拍照」 page of W1 (design r4, profile B31: one visual lead, one main button). The viewfinder 552 x 414 dp (4:3,
 * ≈ 0.83 x 0.62 m) is the picture itself: read-back camera frames through the filter on the headset, the sample or my
 * photo through it otherwise, or the permission card while a permission is missing (S00 series). Under it, 16 dp, the
 * bottom bar (96 dp): thumbnail · shutter · 「滤镜」 + mute. 6 controls with the 3 tabs, mute exempt (B31 exception).
 * After a shot: white flash 120 ms, the frame held 300 ms, then it flies into the thumbnail (350 ms); no card.
 */
@Composable
fun ShootPage(state: FcState) {
    val t = LocalTxt.current
    val shot by FcStore.shotPicture.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            Viewfinder(state, t, shot)
            Spacer(Modifier.height(GAP))
            BottomBar(state, t, shot)
        }
        FlyIn(state, shot)
    }
}

// ---------------------------------------------------------------------------------------------
// the viewfinder
// ---------------------------------------------------------------------------------------------

@Composable
private fun Viewfinder(state: FcState, t: Txt, shot: Bitmap?) {
    val live by FcStore.renderer.live.collectAsStateWithLifecycle()
    val preview by FcStore.renderer.preview.collectAsStateWithLifecycle()
    val square = FcStore.def(state.previewFilter).square
    Box(Modifier.size(VF_W, VF_H).clip(RoundedCornerShape(20.dp)).background(FcColors.Plate), contentAlignment = Alignment.Center) {
        when {
            state.permissionCard -> PermissionCard(state, t)
            state.lensError != null -> LensErrorCard(t)
            state.source == Source.CAMERA -> {
                val frame = live
                when {
                    frame != null -> Image(frame.shown.asImageBitmap(), contentDescription = null, contentScale = if (square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    state.noCamera -> NoCamera(t)
                    else -> FitText(t(R.string.opening_lens), where = "shoot/opening", style = FcType.body, color = FcColors.OnPlate, maxLines = 1)
                }
            }
            else -> {
                preview?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = if (square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                SourceLabel(state, t, Modifier.align(Alignment.TopStart))
            }
        }
        if (state.compare && !state.permissionCard) Chip(t(R.string.compare_on), "shoot/compare", Modifier.align(Alignment.TopEnd).padding(12.dp))
        // S06: the flash, then the frame held still until the fly starts
        if (state.flashing) {
            val flash by produceState(true, FcStore.state.value.thumb?.serial) {
                value = true
                delay(FLASH_MS)
                value = false
            }
            // debug `hold --es state flash` keeps the white (design D36: transitions under 3 s are held for the shot)
            if (flash || FcStore.held == "flash") Box(Modifier.fillMaxSize().background(FcColors.Flash.copy(alpha = 0.9f)))
            else shot?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = if (square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        state.countdown?.let { n ->
            Box(Modifier.size(140.dp).background(FcColors.Plate.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                FitText("$n", where = "shoot/countdown", style = FcType.bigClock, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
            }
        }
        Toast(state, t, Modifier.align(Alignment.BottomCenter))
    }
}

/** Top-left on photo sources: 「示例 · 街角」 / 「我的照片 · name」, plus 「要拍真房间：开权限」 while a permission is off (S01s). */
@Composable
private fun SourceLabel(state: FcState, t: Txt, modifier: Modifier) {
    Row(modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        val label = if (state.source == Source.IMPORT) t(R.string.src_label_import, Filters.shortLutName(state.importName ?: "")) else t(R.string.src_label_sample, FilterText.sample(t, state.sample))
        Chip(label, "shoot/sourceLabel")
        if (!state.permissionGranted && !state.isEmulator) {
            Box(Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(FcColors.Accent).clickable { FcStore.sourceSelected(Source.CAMERA) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                FitText(t(R.string.perm_sample_chip), where = "shoot/permChip", style = FcType.small, color = FcColors.OnAccent, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun Chip(text: String, where: String, modifier: Modifier = Modifier) {
    Box(modifier.background(FcColors.Plate.copy(alpha = 0.82f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        FitText(text, where = where, style = FcType.small, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
    }
}

/** S22 (the emulator): no camera picture, one line and a way to the sample. */
@Composable
private fun NoCamera(t: Txt) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FitText(t(R.string.no_camera), where = "shoot/noCamera", style = FcType.body, color = FcColors.OnPlate, textAlign = TextAlign.Center, maxLines = 3)
        KeyButton(t(R.string.use_sample), "shoot/useSample", accent = true) { FcStore.sourceSelected(Source.SAMPLE) }
    }
}

/** S21: the session could not start; 「再试一次」 or the sample. The technical reason is in the log only. */
@Composable
private fun LensErrorCard(t: Txt) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FitText(t(R.string.lens_unavailable, ""), where = "lensError/title", style = FcType.cardName, color = FcColors.OnPlate, textAlign = TextAlign.Center, maxLines = 2)
        FitText(t(R.string.lens_fail_hint), where = "lensError/hint", style = FcType.small, color = FcColors.OnPlate, textAlign = TextAlign.Center, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyButton(t(R.string.try_again), "lensError/retry", accent = true) { FcStore.lensRetry() }
            KeyButton(t(R.string.use_sample), "lensError/sample", onPlate = true) { FcStore.sourceSelected(Source.SAMPLE) }
        }
    }
}

/**
 * The permission card (design r4 S00 / S00a / S00b / S00c, profile D58): both permissions listed, Spatial Data first,
 * each with what it is for and a status tag; the main button starts the dialogs one by one, asks again, or opens
 * Settings (with where to find it); 「先用示例照片玩」 always works. Settings opens beside this window in Shared Space,
 * so while the card shows it looks again every second (and on resume / focus): both on → the picture within 3 s.
 */
@Composable
private fun PermissionCard(state: FcState, t: Txt) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(PERMISSION_POLL_MS)
            FcStore.permissionRecheck("card poll")
        }
    }
    val prompt = state.permPrompt
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        FitText(t(R.string.perm_title), where = "perm/title", style = FcType.cardName, color = FcColors.OnPlate, maxLines = 1)
        CamPerm.values().forEach { p ->
            PermRow(p, PermCard.chip(p, state.missingPerms, state.permissionDenied, state.blockedPerms, state.asking), t)
        }
        if (prompt != null && prompt.kind != io.embodit.filtercam.domain.CamPerms.Kind.NEED) {
            PermCard.named(prompt)?.let { p -> FitText(t(R.string.perm_missing, FcStore.permName(p), FcStore.permWhy(p)), where = "perm/missing", style = FcType.small, color = FcColors.OnPlate, maxLines = 2) }
        }
        prompt?.blocked?.forEach { p ->
            FitText(t(if (p == CamPerm.SPATIAL_DATA) R.string.perm_path else R.string.perm_path_camera), where = "perm/path", style = FcType.tiny, color = FcColors.OnPlate, maxLines = 3)
        }
        if (state.permWaiting) FitText(t(R.string.perm_waiting), where = "perm/waiting", style = FcType.small, color = FcColors.Accent, maxLines = 1)
        if (prompt == null) FitText(t(R.string.perm_all_on), where = "perm/allOn", style = FcType.small, color = FcColors.OnPlate, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (prompt?.let { PermCard.action(it) }) {
                PermCard.Action.START -> KeyButton(t(R.string.perm_start), "perm/start", accent = true, enabled = state.asking == null) { FcStore.startPermissions("card") }
                PermCard.Action.ASK_AGAIN -> KeyButton(t(R.string.perm_again), "perm/again", accent = true, enabled = state.asking == null) { FcStore.startPermissions("ask again") }
                PermCard.Action.SETTINGS -> KeyButton(t(R.string.perm_go_settings), "perm/settings", accent = true) { FcStore.openPermissionSettings() }
                null -> KeyButton(t(R.string.perm_ok), "perm/ok", accent = true) { FcStore.permReviewDone() }
            }
            if (prompt != null) KeyButton(t(R.string.perm_sample), "perm/sample", onPlate = true) { FcStore.sourceSelected(Source.SAMPLE) }
        }
    }
}

@Composable
private fun PermRow(p: CamPerm, chip: PermChip, t: Txt) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(FcColors.PlateRaised, CircleShape), contentAlignment = Alignment.Center) {
            FitText("${PermCard.step(p)}", where = "perm/step", style = FcType.small, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FitText(FcStore.permName(p), where = "perm/name", style = FcType.body, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
            FitText(FcStore.permWhy(p), where = "perm/why", style = FcType.tiny, color = FcColors.OnPlate, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        val (text, fill, ink) =
            when (chip) {
                PermChip.OFF -> Triple(t(R.string.chip_off), FcColors.PlateRaised, FcColors.OnPlate)
                PermChip.ASKING -> Triple(t(R.string.chip_asking, PermCard.step(p)), FcColors.Accent, FcColors.OnAccent)
                PermChip.ON -> Triple(t(R.string.chip_on), FcColors.PlateRaised, FcColors.OnPlate)
                PermChip.REFUSED -> Triple(t(R.string.chip_refused), FcColors.Danger, FcColors.OnDanger)
            }
        Box(Modifier.background(fill, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            FitText(text, where = "perm/chip", style = FcType.small, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/** The one-line toasts over the viewfinder's lower edge (3 s; the first hint 4 s; save failed 5 s with 「再试一次」). */
@Composable
private fun Toast(state: FcState, t: Txt, modifier: Modifier) {
    val n = state.notice?.takeIf { it.kind !in SIDE_NOTICES }
    LaunchedEffect(n?.serial) {
        if (n == null) return@LaunchedEffect
        delay(
            when (n.kind) {
                NoticeKind.HINT -> HINT_MS
                NoticeKind.SAVE_FAILED -> SAVE_FAILED_MS
                else -> NOTICE_MS
            }
        )
        FcStore.noticeDismissed(n.serial)
    }
    val text =
        when (n?.kind) {
            NoticeKind.HINT -> t(R.string.lens_note)
            NoticeKind.LENS_BACK -> t(R.string.lens_reopen)
            NoticeKind.LENS_LOST -> t(R.string.lens_lost)
            NoticeKind.READ_FAILED -> t(R.string.readback_failed)
            NoticeKind.SAVE_FAILED -> t(R.string.save_failed)
            else -> null
        }
    AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.fillMaxWidth().background(FcColors.Plate.copy(alpha = 0.88f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FitText(text ?: "", where = "shoot/toast", style = FcType.small, color = FcColors.OnPlate, maxLines = 2, modifier = Modifier.weight(1f))
            if (n?.kind == NoticeKind.SAVE_FAILED) {
                Spacer(Modifier.width(12.dp))
                PillButton(t(R.string.retry_save), "shoot/saveRetry", onPlate = true) { FcStore.saveRetry() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// the bottom bar: thumbnail · shutter · 「滤镜」 + mute
// ---------------------------------------------------------------------------------------------

@Composable
private fun BottomBar(state: FcState, t: Txt, shot: Bitmap?) {
    Row(Modifier.width(VF_W).height(BAR_H), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Thumbnail(state, t, shot) }
        Shutter(state, t)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            val sideOn = FcStore.sideShown
            Button(
                onClick = { FcStore.sideToggled() },
                enabled = !state.permissionCard,
                modifier = Modifier.height(64.dp).widthIn(min = 112.dp).then(if (sideOn) Modifier.border(3.dp, FcColors.Accent, RoundedCornerShape(32.dp)) else Modifier),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
            ) {
                Icon(painter = painterResource(R.drawable.ic_filters), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Spacer(Modifier.width(6.dp))
                FitText(t(R.string.filters_btn), where = "shoot/filters", maxLines = 1, softWrap = false, color = PicoTheme.colorScheme.labelPrimary)
            }
            RoundKey(if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (state.muted) t(R.string.unmute) else t(R.string.mute), "shoot/mute") { FcStore.muteToggled() }
        }
    }
}

/**
 * The corner thumbnail (design S06 / S08 / S26): the last shot (the picture right away, the saved file afterwards), a
 * ring while saving, a red 「!」 when the save failed, a dashed frame with the album icon when there are no photos yet.
 * Tap → the album on the newest photo.
 */
@Composable
private fun Thumbnail(state: FcState, t: Txt, shot: Bitmap?) {
    val thumb = state.thumb
    val fromFile by produceState<Bitmap?>(null, thumb?.uri) { value = thumb?.uri?.let { u -> withContext(kotlinx.coroutines.Dispatchers.IO) { FcStore.photos.thumbnail(u, 192, 192) } } }
    // while the fly is on its way the corner still shows the one before (design: it lands, then the corner changes)
    val flying = state.flashing
    val pic = if (thumb != null && thumb.serial > 0 && shot != null && !flying) shot else fromFile
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(thumb?.serial, flying) {
        if (thumb == null || thumb.serial == 0 || flying) return@LaunchedEffect
        delay(FLY_MS.toLong())
        pulse.snapTo(1.12f)
        pulse.animateTo(1f, tween(220))
    }
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(THUMB + 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.scale(pulse.value)
                .size(THUMB)
                .clip(RoundedCornerShape(16.dp))
                .background(FcColors.Plate)
                .border(if (state.saving > 0) 3.dp else 2.dp, if (state.saving > 0) FcColors.Accent else PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(16.dp))
                .spatialHoverEffect()
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClickLabel = t(R.string.gallery)) { FcStore.albumOpen("thumbnail") }
                .controllerHapticFeedback(interactionSource = interaction),
            contentAlignment = Alignment.Center,
        ) {
            if (pic != null) {
                Image(pic.asImageBitmap(), contentDescription = t(R.string.gallery), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painter = painterResource(R.drawable.ic_gallery), contentDescription = t(R.string.gallery), modifier = Modifier.size(30.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
        }
        if (thumb?.failed == true) {
            Box(Modifier.align(Alignment.TopEnd).size(24.dp).background(FcColors.Danger, CircleShape), contentAlignment = Alignment.Center) {
                FitText("!", where = "shoot/thumbFailed", style = FcType.small, color = FcColors.OnDanger, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The shot flies from the viewfinder into the thumbnail (350 ms ease-out) once the flash and the hold are over. */
@Composable
private fun FlyIn(state: FcState, shot: Bitmap?) {
    val serial = state.thumb?.serial ?: 0
    val p = remember { Animatable(1f) }
    LaunchedEffect(serial, state.flashing) {
        if (serial == 0 || state.flashing) {
            if (state.flashing) p.snapTo(0f)
            return@LaunchedEffect
        }
        if (p.value >= 1f) return@LaunchedEffect
        if (FcStore.held == "fly") {
            p.animateTo(0.5f, tween(FLY_MS / 2))
            while (FcStore.held == "fly") delay(100)
        }
        p.animateTo(1f, tween(FLY_MS, easing = FastOutSlowInEasing))
    }
    val pic = shot ?: return
    if (p.value >= 1f || state.flashing) return
    val k = p.value
    val x = lerp(0.dp, 4.dp, k)
    val y = lerp(0.dp, VF_H + GAP + (BAR_H - THUMB) / 2, k)
    val w = lerp(VF_W, THUMB, k)
    val h = lerp(VF_H, THUMB, k)
    Image(pic.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.offset(x, y).size(w, h).clip(RoundedCornerShape(lerp(20.dp, 16.dp, k))))
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
        Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(30.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

/** A 64 dp key on the viewfinder plate (permission card, S21, S22). */
@Composable
private fun KeyButton(label: String, where: String, accent: Boolean = false, onPlate: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = if (accent) FcColors.OnAccent else if (onPlate) FcColors.OnPlate else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(64.dp).widthIn(min = 140.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) FcColors.Accent else if (onPlate) FcColors.PlateRaised else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        FitText(label, where = where, maxLines = 1, softWrap = false, color = ink)
    }
}

/** Design: Ø 88 dp accent disc, dark word, white ring; the countdown number replaces the word (S05). */
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
        FitText(
            if (state.countdown != null) "${state.countdown}" else t(R.string.shutter),
            where = "shoot/shutter",
            style = if (state.countdown != null) FcType.bigClock else FcType.cardName,
            color = if (enabled) FcColors.OnAccent else PicoTheme.colorScheme.labelPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun Badge(text: String, modifier: Modifier) {
    Box(modifier.background(FcColors.Accent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        FitText(text, where = "badge", style = FcType.tiny, color = FcColors.OnAccent, maxLines = 1)
    }
}

/** Notices shown in W2's bottom line, not over the viewfinder. */
private val SIDE_NOTICES = setOf(NoticeKind.V11, NoticeKind.IMPORT_FAILED, NoticeKind.LUT_BAD, NoticeKind.LUT_ADDED)

val VF_W: Dp = 552.dp
val VF_H: Dp = 414.dp
private val GAP = 16.dp
private val BAR_H = 96.dp
private val THUMB = 72.dp
private const val FLASH_MS = 120L
private const val FLY_MS = 350
private const val NOTICE_MS = 3_000L
private const val HINT_MS = 4_000L
private const val SAVE_FAILED_MS = 5_000L
private const val PERMISSION_POLL_MS = 1_000L
