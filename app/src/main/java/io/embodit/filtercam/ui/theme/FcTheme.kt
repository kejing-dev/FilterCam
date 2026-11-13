package io.embodit.filtercam.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.filtercam.app.Lang
import java.util.Locale

/**
 * The design's exact colours (design palette). The only file with colour literals. The accent is the date-stamp orange:
 * solid fills and outlines only, never small text on the glass (design M6 / D13).
 */
object FcColors {
    val Accent = Color(0xFFFF7A1A) // design-style: fixed-figma-color accent: shutter fill, selected card outline
    val OnAccent = Color(0xFF1B1F26) // design-style: fixed-figma-color text on the accent (≈ 9 : 1)
    val ShutterRing = Color(0xFFFFFFFF) // design-style: fixed-figma-color shutter ring (decoration, no text)
    val Danger = Color(0xFFFF5F52) // design-style: fixed-figma-color quit / delete confirm fill
    val OnDanger = Color(0xFF2A0A07) // design-style: fixed-figma-color text on the danger fill
    val Plate = Color(0xFF15181C) // design-style: fixed-figma-color opaque card base in the Stage and behind pictures
    val PlateRaised = Color(0xFF2C323A) // design-style: fixed-figma-color raised button on the plate (OnPlate on it 12.4 : 1)
    val OnPlate = Color(0xFFF2F4F7) // design-style: fixed-figma-color text on the plate (15.9 : 1)
    val Flash = Color(0xFFFFFFFF) // design-style: fixed-figma-color the shutter's white flash
}

/** Strings in the app's own language (design T16: switch without a restart), from res/values(-zh). */
class Txt(context: Context, lang: Lang) {
    private val res: Resources =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(if (lang == Lang.EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE) }).resources

    operator fun invoke(id: Int, vararg args: Any): String = res.getString(id, *args)
}

val LocalTxt = staticCompositionLocalOf<Txt> { error("no Txt") }

/** SpatialUI Vibrant (dark) with the design's interaction and danger roles, the Noto Sans family and the app's language. */
@Composable
fun FcTheme(lang: Lang, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors = remember(system) { system.copy(error = FcColors.Danger, interaction = FcColors.Accent) }
    val family = rememberFcFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    val txt = remember(context, lang) { Txt(context, lang) }
    CompositionLocalProvider(LocalTxt provides txt) { PicoTheme(colorScheme = colors, typography = typography, content = content) }
}
