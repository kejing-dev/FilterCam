package io.embodit.filtercam.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import kotlinx.coroutines.delay

const val LENS_SPOT_ID = "LensSpot"

/**
 * The lens spot window P3 (design, Low Vision v0.1.3): a small planar window to the right of the main window, only there
 * so the SpatialML container is created in front of it (the system centres the container on the focused window and has
 * no placement parameter). No controls here: a focused window is raised over the container. One small line: 「取景窗」
 * and the filter's name, above the container's 0.44 x 0.33 m.
 */
@Composable
fun LensSpot() {
    val state by FcStore.state.collectAsStateWithLifecycle()
    val t = LocalTxt.current
    val focused by LocalSpatialContainerStateManager.current.isFocused
    val gen = remember { FcStore.spotGen }
    LaunchedEffect(focused) {
        FcLog.i("LENS spot focused=$focused")
        if (focused && gen == FcStore.spotGen) {
            delay(SETTLE_MS)
            FcStore.lensSpotReady("spot focused")
        }
    }
    DisposableEffect(Unit) { onDispose { FcStore.lensSpotGone(gen) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(t(R.string.lens_title) + " · " + FilterText.name(t, state.filterId), where = "spot/title", style = FcType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
    }
}

private const val SETTLE_MS = 400L
