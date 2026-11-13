package io.embodit.filtercam.ui.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.Pages
import io.embodit.filtercam.platform.GalleryItem
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The gallery page (design P8, S12): this app's photos, originals marked 「原」. The page draws its own title row with the
 * count. Short-page rule (profile D44, QA #1 q1-1: 2 photos left 69 % blank): with up to [HERO_MAX] photos one big
 * picture (the chosen one, newest first) over a strip of all of them; more photos use the three-column grid; no photos
 * show a big empty frame with a way back to the camera. Each layout fills at least 75 % of the window height.
 */
@Composable
fun GalleryPage(state: FcState) {
    val t = LocalTxt.current
    val items by produceState(initialValue = emptyList<GalleryItem>(), state.galleryTick) { value = withContext(Dispatchers.IO) { FcStore.photos.query() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        TitleRow(t(R.string.gallery), if (items.isEmpty()) null else t(R.string.gallery_count, items.size))
        when {
            items.isEmpty() -> EmptyFrame(t)
            items.size <= HERO_MAX -> Hero(items, t(R.string.raw_badge))
            else ->
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { Thumb(it, t(R.string.raw_badge), Modifier.size(192.dp, 144.dp)) { FcStore.galleryOpen(it.uri, it.raw) } } }
                    }
                }
        }
    }
}

@Composable
private fun TitleRow(title: String, count: String?) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(TITLE_H), verticalAlignment = Alignment.CenterVertically) {
        // same 28 sp as the template's page titles
        FitText(title, where = "gallery/title", style = FcType.title.copy(fontSize = 28.sp), color = ink, maxLines = 1)
        Spacer(Modifier.weight(1f))
        count?.let { FitText(it, where = "gallery/count", style = FcType.small, color = ink, maxLines = 1) }
    }
}

/** 1–[HERO_MAX] photos: the chosen one big (tap: the shot card), all of them in a strip under it. */
@Composable
private fun Hero(items: List<GalleryItem>, rawBadge: String) {
    var chosen by remember { mutableStateOf(0) }
    val i = chosen.coerceIn(0, items.lastIndex)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val item = items[i]
        Thumb(item, rawBadge, Modifier.size(HERO_W, HERO_H), big = true) { FcStore.galleryOpen(item.uri, item.raw) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEachIndexed { n, it ->
                Thumb(it, rawBadge, Modifier.size(88.dp, 66.dp).then(if (n == i) Modifier.border(3.dp, FcColors.Accent, RoundedCornerShape(12.dp)) else Modifier), small = true) { chosen = n }
            }
        }
    }
}

/** No photos yet: a frame as big as the hero picture, the empty line and a way back to the camera page. */
@Composable
private fun EmptyFrame(t: io.embodit.filtercam.ui.theme.Txt) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(Modifier.fillMaxWidth().height(EMPTY_H).clip(RoundedCornerShape(20.dp)).border(2.dp, PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(painter = painterResource(R.drawable.ic_gallery), contentDescription = null, modifier = Modifier.size(72.dp), tint = ink)
            FitText(t(R.string.gallery_empty), where = "gallery/empty", style = FcType.cardName, color = ink, textAlign = TextAlign.Center, maxLines = 2)
            PillButton(t(R.string.gallery_go_shoot), "gallery/goShoot", icon = R.drawable.ic_camera, accent = true) { FcStore.pageSelected(Pages.SHOOT) }
        }
    }
}

@Composable
private fun Thumb(item: GalleryItem, rawBadge: String, modifier: Modifier, big: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val (w, h) = if (big) 960 to 720 else 320 to 240
    val bmp by produceState<Bitmap?>(initialValue = null, item.uri, big) { value = withContext(Dispatchers.IO) { FcStore.photos.thumbnail(item.uri, w, h) } }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(FcColors.Plate).spatialHoverEffect().clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = if (big) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (item.raw && !small) Badge(rawBadge, Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

private const val HERO_MAX = 6
private val TITLE_H = 52.dp
private val HERO_W = 560.dp
private val HERO_H = 420.dp
private val EMPTY_H = 480.dp
