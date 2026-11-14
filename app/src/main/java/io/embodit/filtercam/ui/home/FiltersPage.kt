package io.embodit.filtercam.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.NoticeKind
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.domain.FilterDef
import io.embodit.filtercam.domain.Filters
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt

/**
 * The filter page (design P7, S10 / S11; round 3: reached from 「更多 → 所有滤镜」, no tab of its own): a title row with
 * 「返回」, two columns of big cards (sample through the filter, name, tagline, room mark, v1.1 badge; the whole card picks
 * it, looking at it previews it), then 「我的 LUT ＋」 and the imported LUTs with 「删掉」.
 */
@Composable
fun FiltersPage(state: FcState) {
    val t = LocalTxt.current
    val thumbs by FcStore.renderer.thumbs.collectAsStateWithLifecycle()
    val defs = Filters.builtIn + state.luts.map { Filters.lutFilter(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(t(R.string.back), "filters/back", icon = R.drawable.ic_chevron_left, modifier = Modifier.height(64.dp)) { FcStore.pageSelected(Pages.SHOOT) }
            Spacer(Modifier.width(16.dp))
            FitText(t(R.string.all_filters), where = "filters/title", style = FcType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
        val n = state.notice
        if (n != null && (n.kind == NoticeKind.LUT_BAD || n.kind == NoticeKind.LUT_ADDED || n.kind == NoticeKind.V11)) {
            val line =
                when (n.kind) {
                    NoticeKind.LUT_BAD -> t(R.string.lut_bad)
                    NoticeKind.LUT_ADDED -> t(R.string.lut_added, Filters.shortLutName(n.arg ?: ""))
                    else -> t(R.string.v11_soon, FilterText.name(t, n.arg ?: "F10"))
                }
            FitText(line, where = "filters/notice", style = FcType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
        }
        (defs.map { it as FilterDef? } + listOf(null)).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { d -> if (d != null) BigCard(d, state, thumbs[d.id], t) else AddLutCard(t) }
            }
        }
    }
}

@Composable
private fun BigCard(d: FilterDef, state: FcState, thumb: Bitmap?, t: Txt) {
    val selected = state.filterId == d.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { FcStore.hover(d.id, hovered) }
    Column(
        Modifier.scale(if (hovered) 1.03f else 1f)
            .size(CARD_W, 312.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(FcColors.Plate)
            .border(if (selected) 3.dp else 1.dp, if (selected) FcColors.Accent else PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(18.dp))
            .spatialHoverEffect()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                FcStore.filterChosen(d.id, "filters page")
                if (d.available) {
                    FcStore.moreToggled(false)
                    FcStore.pageSelected(Pages.SHOOT)
                }
            }
    ) {
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            if (thumb != null) Image(thumb.asImageBitmap(), contentDescription = null, contentScale = if (d.square) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (!d.available) Badge(t(R.string.v11_badge), Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        // gate:allow button-gap text lines under the card picture; the only key here is a LUT card's single 「删掉」
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FitText(FilterText.name(t, d.id), where = "filters/name", style = FcType.cardName, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
            FitText(if (d.isLut) FilterText.fullName(t, d.id) else FilterText.tagline(t, d.id), where = "filters/tag", style = FcType.small, color = FcColors.OnPlate, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(FilterText.roomMark(t, d.roomMode), where = "filters/room", style = FcType.small, color = FcColors.OnPlate, maxLines = 2, modifier = Modifier.weight(1f))
                if (d.isLut) {
                    PillButton(t(R.string.delete), "filters/delete", icon = R.drawable.ic_delete) { FcStore.lutDelete(d.id.removePrefix(FilterDef.LUT_PREFIX)) }
                }
            }
        }
    }
}

@Composable
private fun AddLutCard(t: Txt) {
    Column(
        Modifier.size(CARD_W, 312.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PicoTheme.colorScheme.fillTertiary)
            .spatialHoverEffect()
            .clickable { FcStore.pickLut() }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(FcColors.Accent, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(32.dp), tint = FcColors.OnAccent)
        }
        Spacer(Modifier.height(16.dp))
        FitText(t(R.string.my_lut_add), where = "filters/addLut", style = FcType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        FitText(t(R.string.my_lut_hint), where = "filters/addLut", style = FcType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Two cards per row in P1's 616 dp content width (P1 narrowed to 680 dp in v0.1.1; was 336 dp at 760). */
private val CARD_W = 300.dp
