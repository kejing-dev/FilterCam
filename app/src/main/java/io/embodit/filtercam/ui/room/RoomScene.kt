package io.embodit.filtercam.ui.room

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.domain.HeadPose
import io.embodit.filtercam.domain.LevelGate
import io.embodit.filtercam.domain.PanelPlace
import io.embodit.filtercam.domain.RoomLook
import io.embodit.filtercam.domain.RoomLooks
import io.embodit.filtercam.domain.SettleGate
import io.embodit.filtercam.domain.Vec3
import io.embodit.filtercam.platform.ControllerInput
import io.embodit.filtercam.platform.FcLog
import io.embodit.filtercam.platform.HeadTracker
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ActiveRoom {
    @Volatile var scene: RoomScene? = null
}

/** The one per-frame ECS system of room mode. */
class RoomFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        try {
            ActiveRoom.scene?.onFrame(context)
        } catch (t: Throwable) {
            if (errors++ % 120 == 0) FcLog.e("FRAME error: $t", t)
        }
    }

    private companion object {
        var errors = 0
    }
}

/** Id of the room panel P5 in the attachment pool. */
const val ROOM_PANEL = "room"
const val ROOM_PANEL_W = 400f
const val ROOM_PANEL_H = 224f

/**
 * Room mode inside the Mixed Stage (design E1–E4): three camera-anchored layers drawn over the passthrough
 *  - E1 veil: 10 x 10 m quad 2 m ahead, TRANSPARENT (tint + vignette + the instant frame);
 *  - E2 light: the same size 1 cm closer, ADD (leak / neon / scan lines; 4 phases turn over every 1.5 s = 6 s period);
 *  - E2 grain: 2.6 m quad 1.2 m ahead, ADD, 4 grain phases at 4 Hz (design N20 fallback: texture swap, no UV offset);
 *  - E3 date: 0.24 x 0.06 m quad 1.5 m ahead, bottom right, ADD;
 * and the small room panel P5 (a world panel: placed once by the head after it has been still 0.5 s, D29; upright and
 * turned about the vertical only, D9 / D10; stays put, D21). All overlays: depth test off, sorted behind the panel
 * (Low Vision's veil: SortAsUIElementComponent −1), so the panel always draws over them. Main thread only.
 */
class RoomScene {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val scope = MainScope()
    private val headTracker = HeadTracker()
    private val controller = ControllerInput()
    private val settle = SettleGate()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var probe: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var attached = false

    private var veil: UnlitMaterial? = null
    private var light: UnlitMaterial? = null
    private var grain: UnlitMaterial? = null
    private var stamp: UnlitMaterial? = null
    private var veilModel: Entity? = null
    private var lightModel: Entity? = null
    private var grainModel: Entity? = null
    private var stampModel: Entity? = null

    private class Textures(val veil: TextureResource, val light: List<TextureResource>, val grain: List<TextureResource>, val look: RoomLook)

    private val cache = HashMap<String, Textures>()
    private var building: String? = null
    private var shownFilter: String? = null
    private var stampTex: TextureResource? = null
    private var stampKey: String? = null

    private var head: HeadPose? = null
    private var frames = 0L
    private var lastPhaseMs = 0L
    private var lightPhase = 0
    private var grainPhase = 0
    private var lastGrainMs = 0L
    private var placedRecenter = -1
    private var stickHeld = false
    private var drag: Drag? = null

    private class Drag(val start: Vec3) {
        var totalPx = Vec3.ZERO
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached) detach()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val p = Entity().named("contentProbe")
        content.addEntity(p)
        probe = p
        // an entity added to the SpatialView is not at the Stage origin: move the root once (Spatial Ruler)
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val res = SharedResources()
        shared = res
        buildOverlays(root, res)
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(ROOM_PANEL, ROOM_PANEL_W, ROOM_PANEL_H, PanelPlace.ROOM_PANEL.distM)
        headTracker.start()
        controller.start()
        ActiveRoom.scene = this
        attached = true
        settle.ask(SystemClock.elapsedRealtime())
        placedRecenter = FcStore.state.value.room?.recenter ?: 0
        bindPanels(attachments)
        render(FcStore.state.value)
        FcLog.i("ROOM scene attached root_offset=${FcLog.pos(offset.domain())}")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, listOf(ROOM_PANEL), SystemClock.elapsedRealtime())) bindTick.intValue++
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (ActiveRoom.scene === this) ActiveRoom.scene = null
        scope.cancel()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { probe?.destroy(true) }
        cache.clear()
        shared?.close()
        stageRoot = null
        probe = null
        rig = null
        shared = null
        content = null
        veil = null
        light = null
        grain = null
        stamp = null
        FcLog.i("ROOM scene detached")
    }

    // =========================================================================================
    // building
    // =========================================================================================

    private fun material(res: SharedResources, mode: BlendingMode): UnlitMaterial =
        res.keep(
            UnlitMaterial.create(mode).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                setDepthTest(false)
            }
        )

    /** One camera-anchored quad [distM] ahead, [size] metres, offset (x, y) in the head frame. */
    private fun layer(root: Entity, name: String, m: UnlitMaterial, distM: Float, w: Float, h: Float, x: Float = 0f, y: Float = 0f, bias: Float): Entity {
        val anchor = Entity().named("$name-anchor")
        anchor.components.set(AnchorComponent(AnchorTarget.createCameraTarget()).apply { positionOffset = Vector3(x, y, -distM) })
        root.addChild(anchor)
        val model = ModelEntity(MeshCache.quad, m).named(name)
        model.components.set(SortAsUIElementComponent(bias))
        anchor.addChild(model)
        model.setLocal(scale = Vec3(w, h, 1f))
        model.enabled = false
        return model
    }

    private fun buildOverlays(root: Entity, res: SharedResources) {
        val v = material(res, BlendingMode.TRANSPARENT)
        val l = material(res, BlendingMode.ADD)
        val g = material(res, BlendingMode.ADD)
        val s = material(res, BlendingMode.ADD)
        veil = v
        light = l
        grain = g
        stamp = s
        veilModel = layer(root, "veil", v, RoomLooks.VEIL_DIST_M, RoomLooks.VEIL_SIZE_M, RoomLooks.VEIL_SIZE_M, bias = -1.0f)
        lightModel = layer(root, "light", l, RoomLooks.VEIL_DIST_M - 0.01f, RoomLooks.VEIL_SIZE_M, RoomLooks.VEIL_SIZE_M, bias = -0.99f)
        grainModel = layer(root, "grain", g, RoomLooks.GRAIN_DIST_M, RoomLooks.GRAIN_SIZE_M, RoomLooks.GRAIN_SIZE_M, bias = -0.98f)
        // E3: lower right of the view, 1.5 m ahead (design: 视野右下)
        stampModel = layer(root, "stamp", s, 1.5f, 0.24f, 0.06f, x = 0.32f, y = -0.24f, bias = -0.97f)
    }

    private fun texturesFor(id: String) {
        if (cache.containsKey(id) || building == id) return
        val look = RoomLooks.of(id) ?: return
        building = id
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val bitmaps =
                withContext(Dispatchers.Default) {
                    val veil = bmp(RoomLooks.veil(look, VEIL_PX), VEIL_PX, VEIL_PX)
                    val lights = (0 until PHASES).map { bmp(RoomLooks.light(look, LIGHT_PX, it / PHASES.toFloat()), LIGHT_PX, LIGHT_PX) }
                    val grains = (0 until PHASES).map { bmp(RoomLooks.grain(look.grain, GRAIN_PX, it + 1), GRAIN_PX, GRAIN_PX) }
                    Triple(veil, lights, grains)
                }
            // QA #2 q2-1: nine TextureResource.create in a row on the main thread (each waits for its load) froze input
            // for > 5 s → ANR on entering room mode. Each upload now runs off the main thread, one at a time; the layers
            // stay hidden (no black window) until the whole set is in.
            val tex =
                runCatching { uploadSet(bitmaps.first, bitmaps.second, bitmaps.third, look) }.getOrElse {
                    FcLog.e("ROOM textures failed: $it", it)
                    null
                }
            building = null
            if (tex != null) {
                cache[id] = tex
                FcLog.i("ROOM textures $id in ${SystemClock.elapsedRealtime() - t0} ms")
                render(FcStore.state.value)
            }
        }
    }

    private suspend fun uploadSet(veil: Bitmap, lights: List<Bitmap>, grains: List<Bitmap>, look: RoomLook): Textures? {
        val v = upload(veil) ?: return null
        val l = lights.map { upload(it) ?: return null }
        val g = grains.map { upload(it) ?: return null }
        return Textures(v, l, g, look)
    }

    /** One texture upload off the main thread; null once the scene is gone (the caller drops the set). */
    private suspend fun upload(b: Bitmap): TextureResource? {
        if (!attached) return null
        val t = withContext(Dispatchers.IO) { TextureResource.create(b) }
        val res = shared
        if (!attached || res == null) return null
        return res.keep(t)
    }

    private fun bmp(px: IntArray, w: Int, h: Int): Bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

    // =========================================================================================
    // state → entities
    // =========================================================================================

    fun render(state: FcState) {
        if (!attached) return
        val room = state.room ?: return
        val id = room.filterId
        texturesFor(id)
        val tex = cache[id]
        val k = state.strength / 100f
        if (tex != null && shownFilter != id) {
            veil?.setBaseColorTexture(tex.veil)
            light?.setBaseColorTexture(tex.light[lightPhase % PHASES])
            grain?.setBaseColorTexture(tex.grain[grainPhase % PHASES])
            shownFilter = id
            FcLog.i("ROOM filter $id shown")
        }
        // strength: the veil's opacity and the added light's colour (design T2: 全屋罩 α 等比例; N19 run-time change)
        runCatching { veil?.setOpacity(k) }
        runCatching { light?.setBaseColor(Color4(k, k, k, 1f)) }
        runCatching { grain?.setBaseColor(Color4(k, k, k, 1f)) }
        val ready = tex != null && shownFilter == id
        veilModel?.enabled = ready
        lightModel?.enabled = ready && (tex?.look?.let { it.leak > 0f || it.neon > 0f || it.scanlines > 0f } == true)
        grainModel?.enabled = ready && (tex?.look?.grain ?: 0f) > 0f
        val stampText = if (state.stampOn) FcStore.stampTextFor(state.stampFormat) else null
        if (stampText != null && stampText != stampKey) {
            val res = shared
            if (res != null) {
                runCatching { res.keep(TextureResource.create(bmp(RoomLooks.stamp(stampText, 512, 128), 512, 128))) }.getOrNull()?.let {
                    stampTex = it
                    stamp?.setBaseColorTexture(it)
                    stampKey = stampText
                }
            }
        }
        stampModel?.enabled = stampText != null && stampKey == stampText
        if (room.recenter != placedRecenter) {
            placedRecenter = room.recenter
            settle.ask(SystemClock.elapsedRealtime())
        }
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        headTracker.pose()?.let { if (it.position.length > 1e-3f) head = it }
        val h = head
        FcStore.stepWindowGate(now, LevelGate.pitchDeg(h))
        val rig = rig ?: return
        rig.onFrame(now)
        if (h != null) rig.stowBehind(h)
        // P5: placed once the head has been still for 0.5 s (D29), again on 「放到眼前」
        if (settle.placeNow(now, h) && h != null) {
            val pose = PanelPlace.placeUpright(h, PanelPlace.ROOM_PANEL)
            rig[ROOM_PANEL]?.let { slot ->
                slot.locked = false
                slot.show(pose.position)
                slot.faceNow()
                slot.locked = true
            }
            FcLog.i("ROOM panel placed at ${FcLog.pos(pose.position)} yaw=${"%.1f".format(Math.toDegrees(pose.yaw.toDouble()))} head=${FcLog.pos(h.position)}")
        }
        if (h != null) rig.faceViewer(h.position)
        stepDrag()
        // light layer drifts (6 s period over 4 phases), grain at 4 Hz
        val tex = shownFilter?.let { cache[it] }
        if (tex != null) {
            if (now - lastPhaseMs > LIGHT_STEP_MS) {
                lastPhaseMs = now
                lightPhase = (lightPhase + 1) % PHASES
                light?.setBaseColorTexture(tex.light[lightPhase])
            }
            if (now - lastGrainMs > GRAIN_STEP_MS) {
                lastGrainMs = now
                grainPhase = (grainPhase + 1) % PHASES
                grain?.setBaseColorTexture(tex.grain[grainPhase])
            }
        }
        // controller (design T12): stick left / right one step per push (dead zone, D18), A next, B previous
        val x = controller.stick(now)
        if (!stickHeld && abs(x) > STICK_ON) {
            stickHeld = true
            FcStore.roomStep(if (x > 0) 1 else -1, "stick")
        } else if (stickHeld && abs(x) < STICK_OFF) stickHeld = false
        repeat(controller.takeNextPresses()) { FcStore.roomStep(1, "A") }
        repeat(controller.takePrevPresses()) { FcStore.roomStep(-1, "B") }
        if (frames % 600 == 0L) FcLog.i("FRAME n=$frames head=${FcLog.pos(h?.position)} filter=$shownFilter controller(${controller.status()})")
    }

    // ---- dragging the panel (design T13: world-space move, facing frozen while held, D21)

    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
        when (phase) {
            "start" -> {
                val slot = rig?.get(ROOM_PANEL) ?: return
                if (!name.contains(ROOM_PANEL)) return
                drag = Drag(slot.position)
                FcLog.i("DRAG start $name")
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
            }
            else -> {
                val d = drag ?: return
                drag = null
                rig?.get(ROOM_PANEL)?.let { s ->
                    s.locked = false
                    s.faceNow()
                    s.locked = true
                }
                FcLog.i("DRAG end at ${FcLog.pos(rig?.get(ROOM_PANEL)?.position)} from ${FcLog.pos(d.start)}")
            }
        }
    }

    private fun stepDrag() {
        val d = drag ?: return
        val moved = toStage(d.totalPx) ?: return
        val origin = toStage(Vec3.ZERO) ?: return
        rig?.get(ROOM_PANEL)?.moveTo(d.start + (moved - origin))
    }

    private fun toStage(px: Vec3): Vec3? {
        val c = content ?: return null
        val p = probe ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(px.x, px.y, px.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            p.convertPositionTo(local, null).domain()
        }.getOrNull()
    }

    fun dump(): String = "ROOM filter=$shownFilter cached=${cache.keys} panel=${rig?.dump()?.joinToString()} head=${FcLog.pos(head?.position)}"

    companion object {
        private const val VEIL_PX = 1024
        private const val LIGHT_PX = 512
        private const val GRAIN_PX = 512
        private const val PHASES = 4
        private const val LIGHT_STEP_MS = 1_500L
        private const val GRAIN_STEP_MS = 250L
        private const val STICK_ON = 0.6f
        private const val STICK_OFF = 0.3f
    }
}

