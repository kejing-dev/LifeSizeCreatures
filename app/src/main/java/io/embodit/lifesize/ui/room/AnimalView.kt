package io.embodit.lifesize.ui.room

import com.pico.spatial.core.ecs.animation.AnimationPlayConfig
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GroundShadowComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.AnimationResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.lifesize.domain.ModelMetrics
import io.embodit.lifesize.domain.Placed
import io.embodit.lifesize.domain.Quat4
import io.embodit.lifesize.domain.Species
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One animal in the room (design entities `animal`, `floorTape`, `ceilingLine`). Entity tree:
 *
 *     root      on the floor at the middle of the measured length, turned by the placed yaw
 *     |- tape   yellow band from the snout to the end of the measured length, a tick every metre
 *     |- ceil   dashed outline at the ceiling (only while the animal is taller than the room at 1:1)
 *     `- pose   pivot near the snout: the look-at-you turn, the 1:n and appear scale, the float of the shark
 *        |- holder  the file's model, turned so that its head (+Z) points along +X and scaled to true size
 *        `- hit     box collider over the model's box, named `animal:<uid>` (taps, drags)
 *
 * The look turn pivots near the front so that the head swings towards the viewer while the body swings
 * away from them (a whole-body turn about the middle would push a T. rex's head through the viewer).
 */
internal class AnimalView(
    val uid: Int,
    val species: Species,
    parent: Entity,
    private val kit: SceneKit,
) {
    val root: Entity = Entity().named("animal:root:$uid")
    private val tape: Entity = Entity().named("tape:$uid")
    private val pose: Entity = Entity().named("pose:$uid")
    private val holder: Entity = Entity().named("model:$uid")
    val hit: Entity = Entity().named("animal:$uid")
    private var ceil: Entity? = null
    private val ticks = ArrayList<Entity>()
    private val band: Entity
    private val capFront: Entity
    private val capBack: Entity
    private var model: Entity? = null

    /** The model is in place (S12 is over for this animal). */
    val hasModel: Boolean
        get() = model != null

    /** Pivot of the look turn on the length axis (true-size metres from the middle of the measured length). */
    private var pivotX = species.lengthM / 2f - species.fullLengthM * PIVOT_SHARE

    /** The model's rendered box has been measured and the holder scaled from it (appear starts after). */
    var calibrated = false
        private set
    private var holderOffset = Vec3.ZERO
    private var attachedMs = 0L

    private var idle: AnimationResource? = null
    private var react: AnimationResource? = null
    private var reacting: AnimationPlaybackController? = null
    private var animEntity: Entity? = null

    // what is written now (the scene animates these every frame)
    var placed: Placed? = null
        private set
    var lookYaw = 0f
    var appear = 1f
    var opacity = 1f
    var dragCenter: Vec3? = null

    /** Thumbstick turn not yet in the store (added to the placed yaw; the scene commits it on release). */
    var stickYaw = 0f
    private var committedStick = 0f
    private var commitBase: Float? = null

    /** The part of [stickYaw] not handed to the store yet (what a commit may send). */
    val uncommittedStick: Float
        get() = stickYaw - committedStick

    /**
     * [delta] of [stickYaw] was handed to the store while the placed yaw was [base]; it is dropped once the
     * store's yaw arrives in [apply]. Commits before that arrival add up against the first base (QA q2-1:
     * a second commit used to resend the first one).
     */
    fun stickCommitted(base: Float, delta: Float) {
        if (commitBase == null) commitBase = base
        committedStick += delta
    }

    init {
        parent.addChild(root)
        root.addChild(tape)
        root.addChild(pose)
        pose.addChild(holder)
        pose.addChild(hit)
        val s = species.scale
        val m = species.model
        val cx = (m.minX + m.maxX) / 2f
        val zMid = m.maxZ - m.lengthRef / 2f
        // file -> animal: rotate +90 degrees about +Y (head +Z -> +X), scale to true size, put the middle of
        // the measured length on the origin at floor level, then shift by the pivot
        holderOffset = Vec3(-zMid * s, -m.minY * s, cx * s)
        holder.setLocal(
            position = holderOffset - Vec3(pivotX, 0f, 0f),
            rotation = Quat4.axisAngle(Vec3.UP, (PI / 2).toFloat()),
            scale = Vec3(s, s, s),
        )
        hit.components.set(InteractableComponent())
        fitCollider()
        band = kit.boxEntity(kit.tape, "tape:band:$uid").also { tape.addChild(it) }
        capFront = kit.boxEntity(kit.tapeInk, "tape:cap:$uid").also { tape.addChild(it) }
        capBack = kit.boxEntity(kit.tapeInk, "tape:cap:$uid").also { tape.addChild(it) }
        repeat(ceil(species.lengthM).toInt() + 1) { ticks += kit.boxEntity(kit.tapeInk, "tape:tick:$uid").also { tape.addChild(it) } }
        hit.enabled = false
    }

    private fun fitCollider() {
        val size = species.sizeM
        hit.components.set(
            CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())
        )
        hit.setLocal(position = Vec3(species.lengthM / 2f - size.x / 2f - pivotX, size.y / 2f, 0f))
    }

    /**
     * Scales the model from its rendered box (design: "不信文件自带尺寸，按包围盒统一缩放"). The box is
     * read in the holder's parent space, i.e. in the file's scene space (Y up, head +Z), after the first
     * posed frames: skinned files can render at another scale than their bind pose (the T. rex file
     * renders at 1 : 100). Uniform scale = true length / measured length; the measured length is the
     * share [ModelSpec.lengthRef] of the box (tail root for mammals). Returns true once done.
     */
    fun calibrate(nowMs: Long): Boolean {
        if (calibrated) return true
        val m = model ?: return false
        val cached = measured[species.id]
        val c: Vec3
        val h: Vec3
        if (cached != null) {
            c = cached.first
            h = cached.second
        } else {
            // an animated model is measured once its clip has posed it for a while
            if (animEntity != null && nowMs - attachedMs < ANIMATED_SETTLE_MS) return false
            val b = runCatching { m.getVisualBounds(holder) }.getOrNull() ?: return false
            c = b.center.domain()
            h = b.halfExtent.domain()
            if (!c.isFinite() || !h.isFinite() || h.z < 1e-7f || h.y < 1e-7f) return false
            measured[species.id] = c to h
        }
        val spec = species.model
        val share = spec.lengthRef / (spec.maxZ - spec.minZ)
        val lenZ = 2f * h.z
        val s = species.lengthM / (share * lenZ)
        ModelMetrics.sizes[species.id] = Vec3(lenZ * s, 2f * h.y * s, 2f * h.x * s)
        pivotX = species.lengthM / 2f - species.fullLengthM * PIVOT_SHARE
        val zMid = (c.z + h.z) - share * lenZ / 2f
        holderOffset = Vec3(-zMid * s, -(c.y - h.y) * s, c.x * s)
        holder.setLocal(position = holderOffset - Vec3(pivotX, 0f, 0f), scale = Vec3(s, s, s))
        fitCollider()
        runCatching { ceil?.destroy(true) }
        ceil = null
        calibrated = true
        LifeLog.i(String.format(java.util.Locale.US, "MODEL %s calibrated box=%s/%s scale=%.5f size=%s", species.id, LifeLog.pos(c), LifeLog.pos(h), s, LifeLog.pos(species.sizeM)))
        return true
    }

    /** S12 is over: the model joins its holder (a clone of the cached template). */
    fun attachModel(clone: Entity) {
        if (model != null) return
        val fix = Entity().named("fix:$uid")
        holder.addChild(fix)
        fix.setLocal(rotation = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(species.model.fixPitchDeg.toDouble()).toFloat()))
        fix.addChild(clone)
        clone.enabled = true
        model = clone
        attachedMs = android.os.SystemClock.elapsedRealtime()
        castShadows(clone)
        findClips(clone)
        playIdle()
    }

    private fun castShadows(e: Entity) {
        if (e.components[ModelComponent::class.java] != null) runCatching { e.components.set(GroundShadowComponent(true, false)) }
        runCatching { e.getChildren() }.getOrNull()?.forEach { castShadows(it) }
    }

    private fun findClips(model: Entity) {
        val skinned = runCatching { model.findSkinnedMeshEntity() }.getOrNull()?.firstOrNull()
        val carrier = skinned ?: model
        val clips = runCatching { carrier.getAnimationResources() }.getOrNull()?.toList().orEmpty()
        if (clips.isEmpty()) return
        animEntity = carrier
        val names = clips.map { runCatching { it.getName() }.getOrDefault("") }
        fun pick(name: String?): AnimationResource? = name?.let { n -> clips.getOrNull(names.indexOfFirst { it == n || it.endsWith(n) }) }
        idle = pick(species.model.idleClip) ?: clips.first()
        react = pick(species.model.reactClip)
        LifeLog.i("MODEL ${species.id} clips=$names idle=${idle?.let { runCatching { it.getName() }.getOrNull() }} react=${react?.let { runCatching { it.getName() }.getOrNull() }}")
    }

    private fun playIdle() {
        val e = animEntity ?: return
        val clip = idle ?: return
        runCatching { e.playAnimation(clip.repeat(LOOPS), AnimationPlayConfig(transitionDuration = 0.25f)) }.onFailure { LifeLog.w("MODEL ${species.id} idle failed: $it") }
    }

    /** I5: the reaction clip once (when the model has one), then back to idle. */
    fun playReaction() {
        val e = animEntity ?: return
        val clip = react ?: return
        reacting = runCatching { e.playAnimation(clip, AnimationPlayConfig(transitionDuration = 0.2f)) }.getOrNull()
    }

    fun tickAnimation() {
        val r = reacting ?: return
        if (runCatching { r.isComplete() }.getOrDefault(true)) {
            reacting = null
            playIdle()
        }
    }

    /** Applies the placed state (center, yaw, scale) and the animated values. */
    fun apply(p: Placed, floorY: Float, ceilingY: Float?, showCeiling: Boolean, interactable: Boolean) {
        val first = placed == null
        placed = p
        val base = commitBase
        if (base != null && p.yaw != base) {
            stickYaw -= committedStick
            committedStick = 0f
            commitBase = null
        }
        val center = dragCenter ?: p.center
        root.setLocal(position = Vec3(center.x, floorY, center.z), rotation = Quat4.axisAngle(Vec3.UP, p.yaw + stickYaw))
        val k = appear.coerceAtLeast(MIN_APPEAR) / p.scaleN
        pose.setLocal(position = Vec3(pivotX * k, species.floatM / p.scaleN, 0f), rotation = Quat4.axisAngle(Vec3.UP, lookYaw), scale = Vec3(k, k, k))
        drawTape(1f / p.scaleN)
        if (showCeiling && ceilingY != null) {
            val c = ceil ?: buildCeiling()
            c.setLocal(position = Vec3(species.lengthM / 2f - species.fullLengthM / 2f, ceilingY - floorY - 0.01f, 0f))
            c.enabled = true
        } else {
            ceil?.enabled = false
        }
        hit.enabled = interactable && calibrated
        if (first) root.setOpacity(opacity)
    }

    fun setFade(value: Float) {
        opacity = value
        root.setOpacity(value)
    }

    private fun drawTape(k: Float) {
        val l = species.lengthM * k
        band.setLocal(position = Vec3(0f, TAPE_Y, 0f), scale = Vec3(l, TAPE_THICK, TAPE_WIDTH))
        capFront.setLocal(position = Vec3(l / 2f, TAPE_Y + 0.001f, 0f), scale = Vec3(0.012f, TAPE_THICK + 0.002f, TAPE_WIDTH * 2.6f))
        capBack.setLocal(position = Vec3(-l / 2f, TAPE_Y + 0.001f, 0f), scale = Vec3(0.012f, TAPE_THICK + 0.002f, TAPE_WIDTH * 2.6f))
        // a short tick at every true metre from the snout, drawn at the shown scale
        for ((i, t) in ticks.withIndex()) {
            val metre = i + 1
            if (metre >= species.lengthM - 0.05f) {
                t.enabled = false
                continue
            }
            t.enabled = true
            t.setLocal(position = Vec3(l / 2f - metre * k, TAPE_Y + 0.001f, 0f), scale = Vec3(0.008f, TAPE_THICK + 0.002f, TICK_LENGTH))
        }
    }

    private fun buildCeiling(): Entity {
        val mesh = MeshFactory.dashedRect(species.fullLengthM + 0.4f, species.widthM + 0.4f, 0.03f, 0.18f, 0.12f, "ceiling:${species.id}")
        val e = ModelEntity(mesh, kit.ceiling).named("ceiling:$uid")
        root.addChild(e)
        ceil = e
        return e
    }

    /** Debug: the rendered box of the model in [space] (`centre half-extent`), or null without a model. */
    fun boundsIn(space: Entity): String? =
        model?.let { m -> runCatching { m.getVisualBounds(space) }.getOrNull()?.let { "c=${LifeLog.pos(it.center.domain())} h=${LifeLog.pos(it.halfExtent.domain())}" } }

    /** World position of the top of the model's box (for the size tag). */
    fun topOf(p: Placed): Float = (species.floatM + species.sizeM.y) / p.scaleN

    fun destroy() {
        runCatching { animEntity?.stopAllAnimations() }
        runCatching { root.destroy(true) }
    }

    companion object {
        private const val PIVOT_SHARE = 0.18f
        private const val MIN_APPEAR = 0.001f
        private const val ANIMATED_SETTLE_MS = 1500L

        /** Rendered box per species in the file's scene space (centre, half extent), measured once per process. */
        private val measured = HashMap<String, Pair<Vec3, Vec3>>()
        private const val LOOPS = 100_000
        private const val TAPE_Y = 0.004f
        private const val TAPE_THICK = 0.004f
        private const val TAPE_WIDTH = 0.025f
        private const val TICK_LENGTH = 0.06f

        fun wrap(a: Float): Float {
            var d = a
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return d
        }

        fun near(a: Float, b: Float) = abs(wrap(a - b)) < 1e-3f
    }
}
