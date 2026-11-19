package io.embodit.filtercam.ui.home

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.domain.AlbumNav
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.GalleryItem
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 「相册」 page of W1 (design r4 W1.pages.album, S12 / S12e / S13; profile B31: like a phone camera). Opens on the
 * newest photo, big in the viewfinder's place (552 x 414, whole picture on black) with ‹ › over it and 「刚刚 · 滤镜名」;
 * under it the film strip (newest left, the shown one outlined) with 「删掉」 and 「当素材拍」. 「删掉」 asks in a bar
 * that takes the bottom row's place (no window); after it the next photo shows, none left is the empty page. Only the
 * filtered shots are listed; camera originals stay in the system gallery (design c4-5).
 */
@Composable
fun AlbumPage(state: FcState) {
    val t = LocalTxt.current
    val items by produceState(initialValue = null as List<GalleryItem>?, state.galleryTick) { value = withContext(Dispatchers.IO) { FcStore.albumItems() } }
    val list = items ?: return
    if (list.isEmpty()) return Empty(t)
    val i = state.albumIndex.coerceIn(0, list.lastIndex)
    val item = list[i]
    LaunchedEffect(item.uri) { FcLog.i("ALBUM shows ${item.name} (${i + 1}/${list.size}, newest=${i == 0})") }
    Column(Modifier.fillMaxWidth()) {
        Big(item, i, list.size, t)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.width(VF_W).height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.albumDeleteAsk) {
                FitText(t(R.string.delete_ask), where = "album/deleteAsk", style = FcType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                AlbumKey(t(R.string.cancel), "album/cancel") { FcStore.albumDeleteAsk(false) }
                AlbumKey(t(R.string.delete), "album/deleteYes", danger = true) { FcStore.albumDeleteConfirm() }
            } else {
                Strip(list, i, Modifier.weight(1f))
                AlbumKey(t(R.string.delete), "album/delete", icon = R.drawable.ic_delete) { FcStore.albumDeleteAsk(true) }
                AlbumKey(t(R.string.use_as_source), "album/use", icon = R.drawable.ic_camera) { FcStore.albumUse(item.uri) }
            }
        }
    }
}

/** The photo, whole, with ‹ › (greyed at the ends) and 「刚刚 · 周末胶卷」 top left. */
@Composable
private fun Big(item: GalleryItem, i: Int, count: Int, t: Txt) {
    val bmp by produceState<Bitmap?>(null, item.uri) { value = withContext(Dispatchers.IO) { FcStore.photos.thumbnail(item.uri, 1104, 828) } }
    Box(Modifier.size(VF_W, VF_H).clip(RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp).background(FcColors.Plate.copy(alpha = 0.82f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            FitText(whenLine(item, t), where = "album/when", style = FcType.small, color = FcColors.OnPlate, maxLines = 1, softWrap = false)
        }
        // the strip runs newest-first from the left, so ‹ is the newer neighbour and › the older one
        Arrow(R.drawable.ic_chevron_left, t(R.string.album_prev), i > 0, Modifier.align(Alignment.CenterStart).padding(12.dp)) { FcStore.albumStep(-1, count) }
        Arrow(R.drawable.ic_chevron_right, t(R.string.album_next), i < count - 1, Modifier.align(Alignment.CenterEnd).padding(12.dp)) { FcStore.albumStep(1, count) }
    }
}

/** 「刚刚 · 周末胶卷」 / 「12 分钟前 · …」 / 「11-18 21:30 · …」. */
private fun whenLine(item: GalleryItem, t: Txt): String {
    val ago = AlbumNav.minutesAgo(item.takenMs, System.currentTimeMillis())
    val time =
        when {
            ago == null -> SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(item.takenMs))
            ago == 0 -> t(R.string.album_just_now)
            else -> t(R.string.album_min_ago, ago)
        }
    val f = AlbumNav.filterOf(item.name)?.let { FilterText.name(t, it) }
    return if (f != null) "$time · $f" else time
}

/** ‹ / ›: 56 dp round keys over the picture. */
@Composable
private fun Arrow(icon: Int, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(56.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(FcColors.Plate.copy(alpha = 0.82f))
            .spatialHoverEffect()
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp), tint = FcColors.OnPlate)
    }
}

/** The film strip: 64 x 48 dp, newest at the left, the shown one outlined and kept in view. */
@Composable
private fun Strip(list: List<GalleryItem>, i: Int, modifier: Modifier) {
    val st = rememberLazyListState()
    LaunchedEffect(i) { st.animateScrollToItem((i - 1).coerceAtLeast(0)) }
    LazyRow(modifier.height(64.dp), state = st, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(list, key = { _, it -> it.uri }) { n, it ->
            val bmp by produceState<Bitmap?>(null, it.uri) { value = withContext(Dispatchers.IO) { FcStore.photos.thumbnail(it.uri, 128, 96) } }
            Box(
                Modifier.size(64.dp, 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FcColors.Plate)
                    .border(if (n == i) 3.dp else 0.dp, if (n == i) FcColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .spatialHoverEffect()
                    .clickable { FcStore.albumSelect(n, list.size) }
            ) {
                bmp?.let { b -> Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun AlbumKey(label: String, where: String, icon: Int? = null, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (danger) FcColors.OnDanger else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = Modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) FcColors.Danger else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        FitText(label, where = where, maxLines = 1, softWrap = false, color = ink)
    }
}

/** S12e: no photos yet; one line in the picture's place, the tabs are the way back. */
@Composable
private fun Empty(t: Txt) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(Modifier.size(VF_W, VF_H + 16.dp + 96.dp).clip(RoundedCornerShape(20.dp)).border(2.dp, PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(painter = painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(72.dp), tint = ink)
            FitText(t(R.string.gallery_empty), where = "album/empty", style = FcType.cardName, color = ink, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}
