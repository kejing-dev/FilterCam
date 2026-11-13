package io.embodit.filtercam.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FitText

/** A secondary button with an optional icon and its word (profile B19: icons carry text), ≥ 48 dp. */
@Composable
fun PillButton(label: String, where: String, icon: Int? = null, enabled: Boolean = true, modifier: Modifier = Modifier, accent: Boolean = false, onPlate: Boolean = false, onClick: () -> Unit) {
    val ink = if (accent) FcColors.OnAccent else if (onPlate) FcColors.OnPlate else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        enabled = enabled,
        size = ButtonDefaults.Small,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) FcColors.Accent else if (onPlate) FcColors.PlateRaised else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        FitText(label, where = where, maxLines = 1, softWrap = false, color = ink)
    }
}

/**
 * Options of one setting in a row (same page, not page switching: the TabBar does that, profile D8). Each item ≥ 48 dp
 * high (profile D44); selected = accent fill with dark text (design M6).
 */
@Composable
fun <T> Options(items: List<Pair<T, String>>, selected: T, itemWidth: Dp, where: String, onPick: (T) -> Unit) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size + 8.dp).height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { (v, label) ->
            SegmentItem(
                selected = selected == v,
                onClick = { onPick(v) },
                modifier = Modifier.width(itemWidth).height(48.dp),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = FcColors.Accent,
                        selectedItemContentColor = FcColors.OnAccent,
                    ),
                title = { FitText(text = label, where = where, maxLines = 1, softWrap = false) },
            )
        }
    }
}

/** A card drawn over the page inside the window (design P6 / S21); opaque so it reads over any page. */
@Composable
fun OverlayCard(modifier: Modifier, width: Dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .width(width)
            .background(FcColors.Plate, RoundedCornerShape(24.dp))
            .border(1.dp, PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        content()
    }
}
