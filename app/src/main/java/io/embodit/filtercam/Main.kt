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
import io.embodit.filtercam.ui.home.LENS_SPOT_ID
import io.embodit.filtercam.ui.home.LensSpot
import io.embodit.filtercam.ui.room.ROOM_STAGE_ID
import io.embodit.filtercam.ui.room.RoomScreen
import io.embodit.filtercam.ui.theme.FcTheme

/** Id of the default main window (AndroidManifest `pico.spatial.windowcontainer.id`). */
const val HOME_WINDOW_ID = "HomeWindow"

/** The same main window, opened by the room Stage only if restoring the default one fails. */
const val HOME_AGAIN_ID = "Home"
const val HOME_AGAIN_TAG = "home"

/** P1 size (design 760 x 680 dp, narrowed to 680 dp so P1 + the viewfinder fit the default view, QA #1 q1-3). */
val HOME_W = 680.dp
val HOME_H = 680.dp

/** P3 size: the smallest window (320 dp, system minimum) that still shows its title line above the 0.36 m container. */
private val LENS_SPOT_W = 320.dp
private val LENS_SPOT_H = 280.dp

/**
 * App entry (design container ON_PLAIN + secondary STAGE_MIXED, window model window_plus_subwindow + secondary Stage):
 *  - the main window P1 (Shared Space, 680 x 680 dp, native TabBar: 取景 / 滤镜 / 相册 / 设置);
 *  - the lens spot P3 to its right: the SpatialML container P2 is created in front of the focused window, so the session
 *    starts only once this one is focused (Low Vision v0.1.3). P3 (0.5 m) is wider than the 0.44 m container, so the system's
 *    default gap already keeps the container clear of the main window's right edge (design L4) while its right edge
 *    stays inside the default view (QA #1 q1-3: 440 dp + 160 dp offset + 0.6 m pushed it out);
 *  - the shot card P4 is drawn inside the main window (QA #1 q1-2: a Subwindow left of P1 sat half out of view);
 *  - the room Stage (Full Space, Mixed): veil, light layer, date stamp and the small room panel P5.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { Themed { HomeWindow() } }
        WindowContainer(HOME_AGAIN_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = HOME_W, height = HOME_H)) { Themed { HomeWindow(HOME_AGAIN_ID, HOME_AGAIN_TAG) } }
        WindowContainer(
            LENS_SPOT_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = LENS_SPOT_W, height = LENS_SPOT_H),
            placement = { containers.firstOrNull { it.id == HOME_WINDOW_ID || it.id == HOME_AGAIN_ID }?.let { Placement.right(it) } ?: Placement.none() },
        ) { Themed { LensSpot() } }
        Stage(id = ROOM_STAGE_ID) { Themed { RoomScreen() } }
    }

@androidx.compose.runtime.Composable
private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) {
    val state by FcStore.state.collectAsStateWithLifecycle()
    FcTheme(state.lang, content)
}
