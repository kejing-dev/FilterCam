package io.embodit.filtercam.ui.side

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.NoticeKind
import io.embodit.filtercam.app.Source
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.ui.home.Badge
import io.embodit.filtercam.ui.home.FilterText
import io.embodit.filtercam.ui.home.Options
import io.embodit.filtercam.ui.home.PillButton
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

const val SIDE_WINDOW_ID = "FilterSide"

/** Whether W2 is up, and whether the app is the one closing it (else its × did: the store folds it). */
object SideState {
    @Volatile var open = false
    @Volatile var closingByApp = false
}

/**
 * The filter window W2 (design r4: 280 x 576 dp at Placement.right(W1), same height, never over W1). Title 「滤镜 · name」,
 * the cards (232 x 72: the filter's sample through it, name, tagline; looking at one for 0.3 s previews it in the
 * viewfinder, a pinch or the trigger picks it; imported LUTs after the built-in ones), strength, and 「更多」, which turns
 * this window into the settings page S29 in place (no other window). Move / close with the system window bar (D21).
 */
@Composable
fun FilterSide() {
    val state by FcStore.state.collectAsStateWithLifecycle()
    val t = LocalTxt.current
    DisposableEffect(Unit) {
        SideState.open = true
        FcLog.i("SIDE shown")
        onDispose {
            SideState.open = false
            val byApp = SideState.closingByApp
            SideState.closingByApp = false
            FcLog.i("SIDE gone (${if (byApp) "app" else "its ×"})")
            if (!byApp) FcStore.sideClosedBySystem()
        }
    }
    PicoTheme {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            if (state.moreOpen) MorePage(state, t) else FilterList(state, t)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S10: the list
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.FilterList(state: FcState, t: Txt) {
    val thumbs by FcStore.renderer.thumbs.collectAsStateWithLifecycle()
    val defs = Filters.builtIn + state.luts.map { Filters.lutFilter(it) }
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.side_title), where = "side/title", style = FcType.cardName, color = ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(10.dp))
        FitText(FilterText.name(t, state.filterId), where = "side/current", style = FcType.small, color = ink, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    val list = rememberLazyListState()
    val chosen = defs.indexOfFirst { it.id == state.filterId }
    // the chosen card scrolls into view (design W2 layout)
    LaunchedEffect(chosen) { if (chosen >= 0) list.animateScrollToItem(chosen) }
    // gate:allow button-gap a vertical list of 72 dp filter cards (design W2: 8 dp between cards), not side-by-side buttons
    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(defs, key = { it.id }) { d -> Card(d, state, thumbs[d.id], t) }
    }
    SideNotice(state, t)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Spacer(Modifier.width(12.dp))
        FitText("${state.strength}", where = "side/strength", style = FcType.body, color = ink, maxLines = 1, softWrap = false, modifier = Modifier.width(40.dp))
    }
    Spacer(Modifier.height(12.dp))
    WideButton(t(R.string.side_more), "side/more", R.drawable.ic_more) { FcStore.moreToggled(true) }
}

/** One filter card, 232 x 72 dp (design W2): look 0.3 s = preview in the viewfinder, pinch / trigger = pick. */
@Composable
private fun Card(d: FilterDef, state: FcState, thumb: Bitmap?, t: Txt) {
    val selected = state.filterId == d.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(HOVER_MS)
            FcStore.hover(d.id, true)
        } else FcStore.hover(d.id, false)
    }
    val shake = if (state.shakeId == d.id) state.shakeSerial else 0
    Row(
        Modifier.scale(if (hovered) 1.04f else 1f)
            .fillMaxWidth()
            .height(CARD_H)
            .clip(RoundedCornerShape(14.dp))
            .background(FcColors.Plate)
            .border(if (selected) 3.dp else if (hovered) 2.dp else 1.dp, if (selected) FcColors.Accent else if (hovered) FcColors.OnPlate else PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(14.dp))
            .spatialHoverEffect()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { FcStore.filterChosen(d.id, "side card") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(96.dp, CARD_H)) {
            if (thumb != null) Image(thumb.asImageBitmap(), contentDescription = null, contentScale = if (d.square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (!d.available) Badge(t(R.string.v11_badge), Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // gate:allow button-gap text lines beside the card picture; the only key here is a LUT card's single delete icon
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FitText(FilterText.name(t, d.id), where = "side/name", style = FcType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = FcColors.OnPlate, maxLines = 1, softWrap = false)
            FitText(if (d.isLut) t(R.string.lut_tag) else FilterText.tagline(t, d.id), where = "side/tag", style = FcType.tiny, color = FcColors.OnPlate, maxLines = 2)
        }
        if (d.isLut) {
            Box(Modifier.size(48.dp).clickable(onClickLabel = t(R.string.delete)) { FcStore.lutDelete(d.id.removePrefix(FilterDef.LUT_PREFIX)) }, contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = t(R.string.delete), modifier = Modifier.size(22.dp), tint = FcColors.OnPlate)
            }
        }
    }
    if (shake > 0) LaunchedEffect(shake) { FcLog.i("SIDE v1.1 card shake ${d.id}") }
}

/** The W2 bottom line (design S11 / S27 / S28): LUT added or unreadable, import failed, v1.1 card. */
@Composable
private fun SideNotice(state: FcState, t: Txt) {
    val n = state.notice ?: return
    val line =
        when (n.kind) {
            NoticeKind.LUT_BAD -> t(R.string.lut_bad)
            NoticeKind.LUT_ADDED -> t(R.string.lut_added, Filters.shortLutName(n.arg ?: ""))
            NoticeKind.V11 -> t(R.string.v11_soon, FilterText.name(t, n.arg ?: "F10"))
            NoticeKind.IMPORT_FAILED -> t(R.string.import_failed)
            else -> return
        }
    Spacer(Modifier.height(8.dp))
    FitText(line, where = "side/notice", style = FcType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3)
}

// ---------------------------------------------------------------------------------------------
// S29: 「更多」 in place
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.MorePage(state: FcState, t: Txt) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(t(R.string.side_more), where = "more/title", style = FcType.cardName, color = ink, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        PillButton(t(R.string.side_fold), "more/fold") { FcStore.moreToggled(false) }
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FitText(t(R.string.shoot_from), where = "more/source", style = FcType.small, color = ink, maxLines = 1)
        Options(
            listOf(Source.CAMERA to t(R.string.src_camera), Source.SAMPLE to t(R.string.src_sample), Source.IMPORT to t(R.string.src_short_import)),
            state.source,
            74.dp,
            "more/source",
            // 74 dp a segment in the 232 dp window: one size down so "Camera" / "Sample" fit (OVERFLOW in the English tour)
            textStyle = PicoTheme.typography.labelMedium,
        ) { FcStore.sourceSelected(it) }
        when (state.source) {
            Source.SAMPLE -> {
                val i = FilterText.samples.indexOf(state.sample).coerceAtLeast(0)
                WideButton(FilterText.sample(t, state.sample), "more/sampleNext", R.drawable.ic_chevron_right) { FcStore.sampleSelected(FilterText.samples[Math.floorMod(i + 1, FilterText.samples.size)]) }
            }
            Source.IMPORT -> WideButton(t(R.string.pick_photo), "more/pick", R.drawable.ic_gallery) { FcStore.pickPhoto() }
            Source.CAMERA -> Unit
        }
        SwitchRow(t(R.string.stamp), "more/stamp", state.stampOn) { FcStore.stampToggled(it) }
        SwitchRow(t(R.string.timer), "more/timer", state.timerOn) { FcStore.timerToggled(it) }
        WideButton(if (state.compare) t(R.string.compare_on) else t(R.string.compare_off), "more/compare", R.drawable.ic_compare, accent = state.compare) { FcStore.compareToggled() }
        WideButton(t(R.string.room_try), "more/room", R.drawable.ic_room) { FcStore.roomTry() }
        WideButton(t(R.string.my_lut_add), "more/lut", R.drawable.ic_add) { FcStore.pickLut() }
        SideNotice(state, t)
    }
}

@Composable
private fun SwitchRow(label: String, where: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(ROW_H).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        FitText(label, where = where, style = FcType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) { Switch(checked = on, onCheckedChange = onChange) }
    }
}

/** A full-width button with icon and word (W2 is 232 dp inside). */
@Composable
private fun WideButton(label: String, where: String, icon: Int, accent: Boolean = false, height: androidx.compose.ui.unit.Dp = 64.dp, onClick: () -> Unit) {
    val ink = if (accent) FcColors.OnAccent else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(height),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) FcColors.Accent else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
        Spacer(Modifier.width(8.dp))
        FitText(label, where = where, maxLines = 1, softWrap = false, color = ink)
    }
}

private val CARD_H = 72.dp
/** Switch rows (the switch itself sits in a 64 x 48 dp target). */
private val ROW_H = 52.dp

/** Design T1: looking at a card for 0.3 s previews it. */
private const val HOVER_MS = 300L
