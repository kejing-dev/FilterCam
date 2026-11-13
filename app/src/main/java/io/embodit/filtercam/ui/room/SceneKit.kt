package io.embodit.filtercam.ui.room

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.filtercam.domain.Quat4
import io.embodit.filtercam.domain.Vec3
import io.embodit.filtercam.platform.FcLog

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types (ported from Low Vision Walk). The room scene works in Stage metres: every entity hangs below
// the calibrated stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/** Resources shared by entities that come and go: made global and closed with the scene (Spatial Ruler finding). */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * The one mesh of the room overlays: a unit quad in the XY plane facing +Z, made once per process and kept global.
 * The engine samples v = 1 at the bitmap's top row and u = 0 at its left column (Light Planner / Sky Dome finding), so a
 * bitmap drawn the normal way shows upright and unmirrored seen from +Z.
 */
internal object MeshCache {
    val quad: MeshResource by lazy {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val front = Vector3(0f, 0f, 1f)
        val positions = listOf(Vector3(-0.5f, -0.5f, 0f), Vector3(0.5f, -0.5f, 0f), Vector3(0.5f, 0.5f, 0f), Vector3(-0.5f, 0.5f, 0f))
        val uv = listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 1, 2, 0, 2, 3), normals = List(4) { front }, uv0 = uv)
        MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = "quad")
            .also {
                it.toGlobal()
                FcLog.i("MESH quad ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            }
    }
}
