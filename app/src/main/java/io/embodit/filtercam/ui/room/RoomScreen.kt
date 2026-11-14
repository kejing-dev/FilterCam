package io.embodit.filtercam.ui.room

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.openWindowContainer
import io.embodit.filtercam.HOME_AGAIN_ID
import io.embodit.filtercam.HOME_AGAIN_TAG
import io.embodit.filtercam.R
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.ui.home.FilterText
import io.embodit.filtercam.ui.home.PillButton
import io.embodit.filtercam.ui.theme.FcColors
import io.embodit.filtercam.ui.theme.FcType
import io.embodit.filtercam.ui.theme.FitText
import io.embodit.filtercam.ui.theme.LocalTxt
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Id of the room Stage (declared in Main.kt, opened from the main window with style Mixed). */
const val ROOM_STAGE_ID = "RoomStage"

private const val HOME_REOPEN_DELAY_MS = 1_000L

/**
 * Room mode (design S14 – S16, Full Space · Stage Mixed): one full-size SpatialView with the overlays and the room panel.
 * On the way out the main window comes back first, only once the head is level (profile D9), then the Stage closes
 * (closing the last container first hands the focus to the launcher, Low Vision v0.1.0). Going to the background ends
 * room mode (S24).
 */
@Composable
fun RoomScreen() {
    val context = LocalContext.current
    val state by FcStore.state.collectAsStateWithLifecycle()
    val scene = remember { RoomScene() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    DisposableEffect(scene) {
        registerSystem<RoomFrameSystem>()
        FcStore.stageCloser = {
            fun close() =
                scope.launch(Dispatchers.Main.immediate) {
                    FcStore.hiddenHome?.let { (id, tag) ->
                        FcStore.hiddenHome = null
                        val reopened = runCatching { context.applicationContext.openWindowContainer(id, tag) }
                        FcLog.i("HOME reopened before closing the Stage $id/$tag: ${reopened.exceptionOrNull() ?: "ok"}")
                        delay(HOME_REOPEN_DELAY_MS)
                    }
                    runCatching { navigator.closeStage() }.onFailure { FcLog.w("STAGE close failed: $it") }
                }
            if (FcStore.hiddenHome != null) FcStore.openWhenLevel("room back") { close() } else close()
        }
        FcLog.i("STAGE enter")
        var paused = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> paused = true
                    // back from the background: room mode ends (design S24)
                    Lifecycle.Event.ON_RESUME -> if (paused) FcStore.roomBack("resume")
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            FcStore.cancelWindowGate("stage disposed")
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregisterSystem<RoomFrameSystem>()
            FcStore.stageCloser = null
            scene.detach()
            FcStore.stageClosed()
            FcStore.hiddenHome?.let { (id, tag) ->
                FcStore.hiddenHome = null
                val app = context.applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val reopened = runCatching { app.openWindowContainer(id, tag) }
                    FcLog.i("HOME reopened $id/$tag: ${reopened.exceptionOrNull() ?: "ok"}")
                    if (reopened.isFailure) runCatching { app.openWindowContainer(HOME_AGAIN_ID, HOME_AGAIN_TAG) }
                }, HOME_REOPEN_DELAY_MS)
            }
            FcLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                    onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                    onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                ) { value -> scene.onSpatialDrag("move", value) }
            },
        attachments = {
            AttachmentPanel(ROOM_PANEL) {
                Box(Modifier.size(ROOM_PANEL_W.dp, ROOM_PANEL_H.dp)) { if (scene.panelShown[ROOM_PANEL] == true) RoomPanel(state) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * P5 (design: 400 x 180 dp, opaque plate so it reads over the veil): ‹ filter › with the room note, strength, date stamp,
 * 「放到眼前」 and 「回取景台」, and the controller hint.
 */
@Composable
private fun RoomPanel(state: FcState) {
    val t = LocalTxt.current
    val room = state.room ?: return
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxSize().background(FcColors.Plate, shape).border(2.dp, FcColors.Accent, shape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("", "room/prev", icon = R.drawable.ic_chevron_left, modifier = Modifier.width(56.dp), onPlate = true) { FcStore.roomStep(-1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(FilterText.name(t, room.filterId), where = "room/name", style = FcType.cardName, color = FcColors.OnPlate, maxLines = 1, textAlign = TextAlign.Center)
            }
            PillButton("", "room/next", icon = R.drawable.ic_chevron_right, modifier = Modifier.width(56.dp), onPlate = true) { FcStore.roomStep(1) }
        }
        val note = room.switchedFrom?.let { t(R.string.room_switched, FilterText.name(t, it)) } ?: FilterText.roomNote(t, room.filterId) ?: ""
        FitText(note, where = "room/note", style = FcType.small, color = FcColors.OnPlate, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            FitText(t(R.string.strength), where = "room/strength", style = FcType.small, color = FcColors.OnPlate, maxLines = 1, modifier = Modifier.width(56.dp))
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
                colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = FcColors.Accent, progressHighColor = FcColors.Accent),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(60.dp, 48.dp), contentAlignment = Alignment.Center) { Switch(checked = state.stampOn, onCheckedChange = { FcStore.stampToggled(it) }) }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(t(R.string.room_recenter), "room/recenter", icon = R.drawable.ic_recenter, modifier = Modifier.weight(1f), onPlate = true) { FcStore.roomRecenter() }
            PillButton(t(R.string.room_back), "room/back", icon = R.drawable.ic_camera, accent = true, modifier = Modifier.weight(1f)) { FcStore.roomBack() }
        }
        FitText(t(R.string.room_hint), where = "room/hint", style = FcType.tiny, color = FcColors.OnPlate, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
