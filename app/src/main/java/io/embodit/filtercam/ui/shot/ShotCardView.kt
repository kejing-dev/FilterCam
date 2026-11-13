package io.embodit.filtercam.ui.shot

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import io.embodit.filtercam.R
import io.embodit.filtercam.app.CardKind
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.app.ShotCard
import io.embodit.filtercam.ui.home.FilterText
import io.embodit.filtercam.ui.home.OverlayCard
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import io.embodit.filtercam.ui.theme.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shot card P4, drawn over the page inside the main window (QA #1 q1-2: as a Subwindow left of P1 it sat half
 * outside the default view; same move as the P6 cards). Shows the saved shot with its line (S06), only-look (S08n),
 * read-back failed (S08f), save failed (S26) and a gallery picture with 「删掉」 asked twice (S13).
 */
@Composable
fun ShotCardView(card: ShotCard, modifier: Modifier) {
    val t = LocalTxt.current
    OverlayCard(modifier, CARD_W) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (card.kind) {
                CardKind.SAVED, CardKind.ITEM -> Picture(card, Modifier.fillMaxWidth().height(PICTURE_H))
                else -> FilterPicture(card, Modifier.fillMaxWidth().height(PICTURE_H))
            }
            Lines(card, t)
            Buttons(card, t)
        }
    }
}

private val CARD_W = 640.dp
private val PICTURE_H = 280.dp

@Composable
private fun Picture(card: ShotCard, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(initialValue = null, card.uri, card.serial) {
        value = card.uri?.let { u -> withContext(Dispatchers.IO) { runCatching { io.embodit.filtercam.platform.Bitmaps.toBitmap(FcStore.photos.decode(Uri.parse(u), 960)) }.getOrNull() } }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(FcColors.PlateRaised), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** No photo to show (S08n / S08f / S26): the filter's big card instead. */
@Composable
private fun FilterPicture(card: ShotCard, modifier: Modifier) {
    val thumbs by FcStore.renderer.thumbs.collectAsStateWithLifecycle()
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(FcColors.PlateRaised), contentAlignment = Alignment.Center) {
        thumbs[card.filterId]?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Lines(card: ShotCard, t: Txt) {
    val ink = FcColors.OnPlate
    when (card.kind) {
        CardKind.SAVED -> {
            FitText(FilterText.shotLine(t, card.filterId), where = "card/line", style = FcType.cardName, color = ink, maxLines = 2)
            val small = t(R.string.saved_line, card.w, card.h) + if (card.rawSaved) " · " + t(R.string.saved_raw) else ""
            FitText(small, where = "card/saved", style = FcType.small, color = ink, maxLines = 2)
        }
        CardKind.VIEW_ONLY -> FitText(t(R.string.view_only), where = "card/viewOnly", style = FcType.body, color = ink, maxLines = 3)
        CardKind.READ_FAILED -> FitText(t(R.string.readback_failed), where = "card/readFailed", style = FcType.body, color = ink, maxLines = 3)
        CardKind.SAVE_FAILED -> FitText(t(R.string.save_failed), where = "card/saveFailed", style = FcType.body, color = ink, maxLines = 3)
        CardKind.ITEM -> if (card.deleteAsked) FitText(t(R.string.delete_ask), where = "card/deleteAsk", style = FcType.cardName, color = ink, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (card.kind == CardKind.SAVED && card.deleteAsked) FitText(t(R.string.delete_ask), where = "card/deleteAsk", style = FcType.cardName, color = ink, maxLines = 1)
}

@Composable
private fun Buttons(card: ShotCard, t: Txt) {
    // 「换个滤镜重出」 gets its own row: three buttons in 480 dp cut the English words (D13)
    if (card.kind == CardKind.SAVED && !card.deleteAsked) CardButton(t(R.string.shot_redo), Modifier.fillMaxWidth()) { FcStore.cardRedo() }
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (card.deleteAsked) {
            CardButton(t(R.string.cancel), Modifier.weight(1f)) { FcStore.cardDeleteAsk(false) }
            CardButton(t(R.string.delete), Modifier.weight(1f), danger = true) { FcStore.cardDeleteConfirm() }
            return@Row
        }
        when (card.kind) {
            CardKind.SAVED -> {
                CardButton(t(R.string.shot_again), Modifier.weight(1f), accent = true) { FcStore.cardAgain() }
                CardButton(t(R.string.delete), Modifier.weight(1f)) { FcStore.cardDeleteAsk(true) }
            }
            CardKind.ITEM -> {
                CardButton(t(R.string.gallery_use), Modifier.weight(1.3f), accent = true) { card.uri?.let { FcStore.galleryUse(it) } }
                CardButton(t(R.string.delete), Modifier.weight(1f)) { FcStore.cardDeleteAsk(true) }
                CardButton(t(R.string.got_it), Modifier.weight(1f)) { FcStore.cardOk() }
            }
            CardKind.VIEW_ONLY -> {
                CardButton(t(R.string.go_grant), Modifier.weight(1f), accent = true) { FcStore.cardGrant() }
                CardButton(t(R.string.got_it), Modifier.weight(1f)) { FcStore.cardOk() }
            }
            CardKind.READ_FAILED -> CardButton(t(R.string.got_it), Modifier.weight(1f), accent = true) { FcStore.cardOk() }
            CardKind.SAVE_FAILED -> {
                CardButton(t(R.string.try_again), Modifier.weight(1f), accent = true) { FcStore.cardRetry() }
                CardButton(t(R.string.got_it), Modifier.weight(1f)) { FcStore.cardOk() }
            }
        }
    }
}

@Composable
private fun CardButton(label: String, modifier: Modifier, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (accent) FcColors.OnAccent else if (danger) FcColors.OnDanger else FcColors.OnPlate
    val fill = if (accent) FcColors.Accent else if (danger) FcColors.Danger else FcColors.PlateRaised
    Button(onClick = onClick, modifier = modifier.height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink)) {
        FitText(label, where = "card/button", maxLines = 1, softWrap = false, color = ink)
    }
}

