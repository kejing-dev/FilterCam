package io.embodit.filtercam

import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.ui.home.HomeWindow
import io.embodit.filtercam.ui.room.ROOM_STAGE_ID
import io.embodit.filtercam.ui.room.RoomScreen
import io.embodit.filtercam.ui.side.FilterSide
import io.embodit.filtercam.ui.side.SIDE_WINDOW_ID
import io.embodit.filtercam.ui.theme.FcTheme

/** Id of the default camera window W1 (AndroidManifest `pico.spatial.windowcontainer.id`). */
const val HOME_WINDOW_ID = "HomeWindow"

/** The same camera window, opened by 「叫回面前」 and by the room Stage if restoring the default one fails. */
const val HOME_AGAIN_ID = "Home"
const val HOME_AGAIN_TAG = "home"

/** W1 (design r4 windowLayout: 600 x 576 dp, ≈ 0.90 x 0.86 m at 1.5 mm / dp). */
val HOME_W = 600.dp
val HOME_H = 576.dp

/** W2, the filter window (design r4: 280 x 576 dp, the same height as W1). */
private val SIDE_W = 280.dp
private val SIDE_H = 576.dp

/**
 * App entry (design r4 container ON_PLAIN + secondary STAGE_MIXED):
 *  - the camera window W1 (Shared Space, 600 x 576 dp, native TabBar 拍照 / 相册 / 设置): the viewfinder is drawn inside
 *    it from read-back camera frames, the shutter right under it (user 2026-11-18: "the main window is the viewfinder
 *    plus a shutter");
 *  - the filter window W2 at Placement.right(W1), same depth, the system gap between them, so the two never overlap
 *    from the eye (design windowLayout, B31 补); opened and folded by W1;
 *  - the room Stage (Full Space, Mixed): veil, light layer, date stamp and the small room panel P5.
 * No SpatialML container window, no LensSpot window, no shot card or guide window any more (design panelsRemoved).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { Themed { HomeWindow() } }
        WindowContainer(HOME_AGAIN_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = HOME_W, height = HOME_H)) { Themed { HomeWindow(HOME_AGAIN_ID, HOME_AGAIN_TAG) } }
        WindowContainer(
            SIDE_WINDOW_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = SIDE_W, height = SIDE_H),
            placement = { containers.lastOrNull { it.id == HOME_WINDOW_ID || it.id == HOME_AGAIN_ID }?.let { Placement.right(it) } ?: Placement.none() },
        ) { Themed { FilterSide() } }
        Stage(id = ROOM_STAGE_ID) { Themed { RoomScreen() } }
    }

@androidx.compose.runtime.Composable
private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) {
    val state by FcStore.state.collectAsStateWithLifecycle()
    FcTheme(state.lang, content)
}
