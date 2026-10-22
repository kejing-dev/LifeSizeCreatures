package io.embodit.lifesize.ui.room

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.lifesize.domain.Quat4
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.ui.theme.LifeColors

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

/** Compose colours are gamma sRGB in 0..1, which is what `Color4` expects. */
internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

/** Overall opacity of an entity and its children (fades only; no state depends on it). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (measured in the Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user entity
 * is destroyed; shared ones are made global and closed with the scene (Spatial Ruler finding).
 */
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
 * Scene-lifetime materials and unit meshes. Domain colours are unlit with tone mapping off so that they
 * keep their exact value (Spatial Ruler: tape yellow arrives dull with tone mapping on).
 */
internal class SceneKit(private val shared: SharedResources) {
    val tape: Material = unlit(LifeColors.TapeYellow)
    val tapeInk: Material = unlit(LifeColors.TapeInk)
    val ceiling: Material = unlit(LifeColors.CeilingAlert, twoSided = true)
    val ghostFill: Material = sheer(LifeColors.TapeYellow, 0.18f)
    val ghostEdge: Material = unlit(LifeColors.TapeYellow, twoSided = true)
    val shadowless: Material = sheer(Color.Black, 0.0f)

    /** Unit box (1 m cube), scaled per use: tape band, ticks, ghost edges, the floor catcher's look. */
    val box: MeshResource = shared.keep(MeshResource.createBox(Vector3(1f, 1f, 1f)))

    /** Unit square in the XY plane facing +Z, scaled per use: the ghost footprint, the "you" figure. */
    val plane: MeshResource = shared.keep(MeshResource.createPlane(1f, 1f))

    /** Arrow head for the ghost: a cone along +Y. */
    val cone: MeshResource = shared.keep(MeshResource.createCone(1f, 0.5f))

    fun unlit(color: Color, twoSided: Boolean = false): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through unlit: the colour is opaque, the material's opacity carries the share. */
    fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** A see-through textured unlit material (the "you" figure). */
    fun textured(bitmap: Bitmap): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(TextureResource.create(bitmap))
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    fun boxEntity(material: Material, name: String): Entity = ModelEntity(box, material).named(name)

    fun planeEntity(material: Material, name: String): Entity = ModelEntity(plane, material).named(name)
}

/** Programmatic meshes (`MeshModel`). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)

    /**
     * A dashed rectangle outline in the XZ plane (y = 0), centred on the origin: [lengthX] x [widthZ],
     * stroke [stroke] wide, dashes [dash] long with [gap] between them. Facing up; draw it two-sided.
     */
    fun dashedRect(lengthX: Float, widthZ: Float, stroke: Float, dash: Float, gap: Float, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun quad(x0: Float, z0: Float, x1: Float, z1: Float) {
            val base = positions.size
            positions += Vector3(x0, 0f, z0)
            positions += Vector3(x1, 0f, z0)
            positions += Vector3(x1, 0f, z1)
            positions += Vector3(x0, 0f, z1)
            repeat(4) { normals += UP }
            indices += listOf(base, base + 2, base + 1, base, base + 3, base + 2)
        }
        val hx = lengthX / 2f
        val hz = widthZ / 2f
        val h = stroke / 2f
        fun dashesX(z: Float) {
            var x = -hx
            while (x < hx) {
                quad(x, z - h, minOf(x + dash, hx), z + h)
                x += dash + gap
            }
        }
        fun dashesZ(x: Float) {
            var z = -hz
            while (z < hz) {
                quad(x - h, z, x + h, minOf(z + dash, hz))
                z += dash + gap
            }
        }
        dashesX(-hz)
        dashesX(hz)
        dashesZ(-hx)
        dashesZ(hx)
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(hx + h, 0.001f, hz + h))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }
}
