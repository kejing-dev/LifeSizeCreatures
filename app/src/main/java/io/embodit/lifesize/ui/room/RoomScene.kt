package io.embodit.lifesize.ui.room

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.lifesize.app.CreatureState
import io.embodit.lifesize.app.CreatureStore
import io.embodit.lifesize.domain.HeadPose
import io.embodit.lifesize.domain.Phase
import io.embodit.lifesize.domain.Placed
import io.embodit.lifesize.domain.Quat4
import io.embodit.lifesize.domain.RoomRules
import io.embodit.lifesize.domain.Roster
import io.embodit.lifesize.domain.Species
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.domain.yawOf
import io.embodit.lifesize.platform.LifeLog
import io.embodit.lifesize.platform.sense.PlaneTracker
import io.embodit.lifesize.platform.tracking.ControllerRay
import io.embodit.lifesize.platform.tracking.HeadTracker
import io.embodit.lifesize.ui.room.debug.Probe
import io.embodit.lifesize.ui.theme.LifeColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object ActiveScene {
    @Volatile var scene: RoomScene? = null
}

/** The one per-frame ECS system of the app. */
class RoomFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool: a size tag and a tape reading per animal (at most two), the "you" label, the info card. */
object PanelIds {
    const val TAG0 = "sizeTag0"
    const val TAG1 = "sizeTag1"
    const val READ0 = "tapeRead0"
    const val READ1 = "tapeRead1"
    const val YOU = "youLabel"
    const val CARD = "infoCard"
    val pool = listOf(TAG0, TAG1, READ0, READ1, YOU, CARD)

    fun tag(i: Int) = if (i == 0) TAG0 else TAG1

    fun read(i: Int) = if (i == 0) READ0 else READ1
}

/**
 * The 3D side of the app inside the Mixed Stage: one calibrated stage root, the model cache, the animals
 * with their tapes, the "you" figure, the floor catcher, the placing ghost and the panel pool. Per-frame
 * work happens in [onFrame] and never goes through Compose; [render] applies a new [CreatureState].
 * Main thread only.
 */
class RoomScene : CreatureStore.SceneHost, Probe.Host {

    /** Panel id -> the panel is in view. A pooled panel composes its content only while this is true. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    /** Plays the animal's call (I13); set by the Stage composable, which owns the audio player. */
    var onCall: ((Species) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val headTracker = HeadTracker()
    private val planes = PlaneTracker()
    private val controller = ControllerRay()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shelf: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var kit: SceneKit? = null
    private var attached = false

    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastHeadSampleMs = 0L
    private var lastLogMs = 0L

    private var state = CreatureState()

    // ---- models
    private val templates = HashMap<String, Entity>()
    private val loading = HashSet<String>()
    private val queue = ArrayDeque<String>()
    private var loadBusy = false

    // ---- room content
    private val views = LinkedHashMap<Int, AnimalView>()
    private val dying = ArrayList<Pair<AnimalView, Long>>()
    private val appearStart = HashMap<Int, Long>()
    private val looks = HashMap<Int, Look>()
    private var floorCatcher: Entity? = null
    private var you: Entity? = null
    private var ghost: Ghost? = null
    private var cardAt: Vec3? = null
    private var cardFor: Int? = null
    private var debugGhost: Vec3? = null

    private class Look(val startMs: Long, val target: Float) {
        var returnMs = -1L
    }

    private class Drag(val uid: Int, val grab: Vec3, val start: Vec3) {
        var totalPx = Vec3.ZERO
        var moved = false
        var center: Vec3 = start
    }

    private var drag: Drag? = null
    private var lastDragEndMs = 0L

    // ---- thumbstick turn (v0.1.1)
    private var stickUid: Int? = null
    private var stickRate = 0f
    private var lastFrameMs = 0L
    private var debugStick = 0f
    private var debugStickUntilMs = 0L

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z for a full-size
        // view, Spatial Ruler). Move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val res = SharedResources()
        shared = res
        val k = SceneKit(res)
        kit = k
        // templates wait here, disabled; the room clones them
        shelf = Entity().named("shelf").also {
            root.addChild(it)
            it.enabled = false
        }
        floorCatcher =
            Entity().named("floor").also {
                it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(CATCHER_M, CATCHER_THICK_M, CATCHER_M))), physicsMaterial = PhysicsMaterialResource()))
                it.components.set(InteractableComponent())
                root.addChild(it)
                it.enabled = false
            }
        you = k.planeEntity(k.textured(youBitmap()), "you").also {
            root.addChild(it)
            it.enabled = false
        }
        ghost = Ghost(root, k)
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.TAG0, SizeTagWidth.value, SizeTagHeight.value, 1f)
        rig.world(PanelIds.TAG1, SizeTagWidth.value, SizeTagHeight.value, 1f)
        rig.world(PanelIds.READ0, ReadingWidth.value, ReadingHeight.value, 1f)
        rig.world(PanelIds.READ1, ReadingWidth.value, ReadingHeight.value, 1f)
        rig.world(PanelIds.YOU, YouLabelWidth.value, YouLabelHeight.value, 1f)
        rig.world(PanelIds.CARD, InfoCardWidth.value, InfoCardHeight.value, CARD_SCALE)
        headTracker.start()
        controller.start()
        planes.start { room -> CreatureStore.roomUpdated(room) }
        ActiveScene.scene = this
        CreatureStore.sceneHost = this
        Probe.host = this
        attached = true
        bindPanels(attachments)
        // design animal.budget: all eight are preloaded while the floor is found (about 190 000 triangles)
        Roster.all.forEach { requestModel(it.id) }
        render(CreatureStore.state.value)
        LifeLog.i("SCENE attached root_offset=${LifeLog.pos(offset.domain())}")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    /** Stops what [attach] started (providers, loads) and destroys every entity (lifecycle rule). */
    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        if (CreatureStore.sceneHost === this) CreatureStore.sceneHost = null
        if (Probe.host === this) Probe.host = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        headTracker.stop()
        controller.stop()
        planes.stop()
        panelShown.clear()
        views.values.forEach { it.destroy() }
        views.clear()
        dying.forEach { it.first.destroy() }
        dying.clear()
        looks.clear()
        appearStart.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        templates.clear()
        loading.clear()
        queue.clear()
        stageRoot = null
        contentProbe = null
        shelf = null
        rig = null
        kit = null
        shared = null
        content = null
        ghost = null
        you = null
        floorCatcher = null
        hasHead = false
        LifeLog.i("SCENE detached")
    }

    // =========================================================================================
    // models (S12)
    // =========================================================================================

    override fun head(): HeadPose? = if (hasHead) head else null

    override fun isModelReady(speciesId: String): Boolean = templates.containsKey(speciesId) && !Probe.slowLoad

    override fun requestModel(speciesId: String) {
        if (templates.containsKey(speciesId)) {
            if (Probe.slowLoad) main.postDelayed({ CreatureStore.modelReady(speciesId) }, SLOW_LOAD_MS)
            return
        }
        if (speciesId in loading || speciesId in queue) {
            // asked for while preloading: move it to the front
            if (queue.remove(speciesId)) queue.addFirst(speciesId)
            return
        }
        queue.addLast(speciesId)
        pump()
    }

    private fun pump() {
        if (loadBusy || !attached) return
        val id = queue.removeFirstOrNull() ?: return
        val species = Roster[id] ?: return
        loadBusy = true
        loading += id
        val t0 = SystemClock.elapsedRealtime()
        scope.launch {
            val result = runCatching { Entity.loadSuspend("asset://" + species.model.asset) }
            loading -= id
            loadBusy = false
            val entity = result.getOrNull()
            val shelf = shelf
            if (entity == null || shelf == null || !attached) {
                LifeLog.e("MODEL $id load failed: ${result.exceptionOrNull()}")
                if (attached) CreatureStore.modelFailed(id)
            } else {
                shelf.addChild(entity.named("template:$id"))
                templates[id] = entity
                val box = runCatching { entity.getVisualBounds(entity) }.getOrNull()
                LifeLog.i("MODEL $id loaded in ${SystemClock.elapsedRealtime() - t0} ms bounds=${box?.let { "c=${LifeLog.pos(it.center.domain())} h=${LifeLog.pos(it.halfExtent.domain())}" }}")
                if (Probe.slowLoad) main.postDelayed({ CreatureStore.modelReady(id) }, SLOW_LOAD_MS) else CreatureStore.modelReady(id)
                render(state)
            }
            pump()
        }
    }

    private fun cloneOf(id: String): Entity? = templates[id]?.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))

    // =========================================================================================
    // model -> entities
    // =========================================================================================

    /** Idempotent: which animals stand where, which panels show. */
    fun render(next: CreatureState) {
        val prev = state
        state = next
        if (!attached) return
        val root = stageRoot ?: return
        val k = kit ?: return
        val room = next.room
        val now = SystemClock.elapsedRealtime()
        floorCatcher?.let { c ->
            val on = next.placing != null && room != null && next.phase == Phase.ROOM
            if (on && room != null) c.setLocal(position = Vec3(head.position.x, room.floorY - CATCHER_THICK_M / 2f, head.position.z))
            c.enabled = on
        }
        // animals: reconcile by uid
        val alive = next.animals.associateBy { it.uid }
        for ((uid, v) in views.entries.toList()) {
            if (uid !in alive) {
                views.remove(uid)
                looks.remove(uid)
                dying += v to now
                v.hit.enabled = false
            }
        }
        for (p in next.animals) {
            var v = views[p.uid]
            if (v == null) {
                v = AnimalView(p.uid, p.species, root, k)
                views[p.uid] = v
                LifeLog.i("ANIMAL add ${p.speciesId}#${p.uid} center=${LifeLog.pos(p.center)}")
            }
            if (!v.hasModel && !p.loading) {
                cloneOf(p.speciesId)?.let {
                    v.attachModel(it)
                    // it grows in once its rendered box is measured (animate -> calibrate)
                    v.appear = 0f
                }
            }
            val before = prev.animal(p.uid)
            if (before != null && v.calibrated && before.center != p.center && drag?.uid != p.uid && (before.center - p.center).length > REPLACE_M && before.scaleN == p.scaleN) {
                // I3: placed again somewhere else: it appears there anew
                v.appear = 0f
                appearStart[p.uid] = now
            }
        }
        if (next.cardUid != cardFor) {
            cardFor = next.cardUid
            cardAt = next.animal(next.cardUid)?.let { cardSpot(it) }
        }
        applyAll(now)
        val rig = rig ?: return
        for (i in 0 until 2) {
            val p = next.animals.getOrNull(i)
            val v = p?.let { views[it.uid] }
            if (p != null && v != null && v.calibrated && next.draggingUid != p.uid) rig[PanelIds.tag(i)]?.show() else rig[PanelIds.tag(i)]?.hide()
            if (p != null && v != null) rig[PanelIds.read(i)]?.show() else rig[PanelIds.read(i)]?.hide()
        }
        if (nearestAnimal() != null) rig[PanelIds.YOU]?.show() else rig[PanelIds.YOU]?.hide()
        val c = cardAt
        if (next.cardUid != null && c != null) rig[PanelIds.CARD]?.show(at = c) else rig[PanelIds.CARD]?.hide()
        placePanels()
    }

    /**
     * I5: info card at eye height, 1.2 m away, 25 degrees beside the line to the animal. Design: to the
     * left. The guide window (Shared Space) is drawn over Stage content, so the card takes the side away
     * from the window and keeps at least [CARD_CLEAR_RAD] from it. The window is assumed where it opens:
     * straight ahead of the Stage origin (-Z); the app cannot read where the user moved it.
     */
    private fun cardSpot(p: Placed): Vec3 {
        val h = head.position
        var to = Vec3(p.center.x - h.x, 0f, p.center.z - h.z)
        if (to.length < 1e-3f) to = Vec3(head.forward.x, 0f, head.forward.z)
        if (to.length < 1e-3f) to = Vec3(0f, 0f, -1f)
        val animalYaw = yawOf(to.normalized())
        val windowYaw = yawOf(Vec3(0f, 0f, -1f))
        val left = animalYaw + RoomRules.CARD_LEFT_RAD
        val right = animalYaw - RoomRules.CARD_LEFT_RAD
        var yaw = if (abs(AnimalView.wrap(left - windowYaw)) >= abs(AnimalView.wrap(right - windowYaw)) - 1e-3f) left else right
        val off = AnimalView.wrap(yaw - windowYaw)
        if (abs(off) < CARD_CLEAR_RAD) yaw = windowYaw + (if (off >= 0f) CARD_CLEAR_RAD else -CARD_CLEAR_RAD)
        return h + io.embodit.lifesize.domain.snoutDir(yaw) * RoomRules.CARD_DISTANCE_M
    }

    private fun nearestAnimal(): Pair<Placed, AnimalView>? =
        state.animals.mapNotNull { p -> views[p.uid]?.takeIf { it.calibrated }?.let { p to it } }.minByOrNull { (p, _) ->
            Vec3(p.center.x - head.position.x, 0f, p.center.z - head.position.z).length
        }

    private fun applyAll(now: Long) {
        val room = state.room ?: return
        val interactable = state.placing == null
        for (p in state.animals) {
            val v = views[p.uid] ?: continue
            val fit = state.fit(p)
            v.apply(p, room.floorY, room.ceilingY, showCeiling = fit.overHighM != null && p.scaleN == 1, interactable = interactable && drag?.uid != p.uid)
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
        headTracker.pose()?.let {
            if (it.position.length > 1e-3f) {
                head = it
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head)
        if (!attached) return
        val room = state.room
        if (hasHead && room != null && now - lastHeadSampleMs > HEAD_SAMPLE_MS) {
            lastHeadSampleMs = now
            CreatureStore.headSample(head.position.y - room.floorY)
        }
        animate(now)
        stepDrag()
        stepStick(now)
        applyAll(now)
        placeYou()
        placeGhost()
        placePanels()
        rig.faceViewer(head.position)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            LifeLog.i("FRAME n=$frames head=${LifeLog.pos(head.position)} state=${state.roomStateId} animals=${views.size} templates=${templates.keys.sorted()} controller(${controller.status()})")
        }
    }

    private fun animate(now: Long) {
        for ((uid, v) in views) {
            if (v.hasModel && !v.calibrated && v.calibrate(now)) {
                appearStart[uid] = now
                main.post { render(CreatureStore.state.value) }
            }
        }
        // appear (0.4 s growing in) and fade out (0.3 s) of animals that left
        for ((uid, t0) in appearStart.entries.toList()) {
            val v = views[uid] ?: run {
                appearStart.remove(uid)
                null
            } ?: continue
            val q = ((now - t0) / APPEAR_MS).coerceIn(0f, 1f)
            v.appear = easeOut(q)
            if (q >= 1f) appearStart.remove(uid)
        }
        val it = dying.iterator()
        while (it.hasNext()) {
            val (v, t0) = it.next()
            val q = ((now - t0) / FADE_MS).coerceIn(0f, 1f)
            v.setFade(1f - q)
            if (q >= 1f) {
                v.destroy()
                it.remove()
            }
        }
        // I5: turn to look (0.5 s), hold 3 s (longer while frozen), turn back (0.5 s)
        for ((uid, look) in looks.entries.toList()) {
            val v = views[uid] ?: continue
            val elapsed = now - look.startMs
            v.lookYaw =
                when {
                    elapsed < RoomRules.TURN_MS -> look.target * easeInOut(elapsed / RoomRules.TURN_MS.toFloat())
                    look.returnMs < 0 -> {
                        if (elapsed > RoomRules.TURN_MS + RoomRules.LOOK_HOLD_MS && !CreatureStore.frozen) look.returnMs = now
                        look.target
                    }
                    else -> {
                        val q = ((now - look.returnMs) / RoomRules.TURN_MS.toFloat()).coerceIn(0f, 1f)
                        if (q >= 1f) looks.remove(uid)
                        look.target * (1f - easeInOut(q))
                    }
                }
            v.tickAnimation()
        }
        for (v in views.values) v.tickAnimation()
    }

    /** I5: turn about the front pivot until the snout points at the viewer. */
    override fun react(uid: Int) {
        val p = state.animal(uid) ?: return
        val v = views[uid] ?: return
        val toHead = Vec3(head.position.x - p.center.x, 0f, head.position.z - p.center.z)
        val target = if (toHead.length < 1e-3f) 0f else AnimalView.wrap(yawOf(toHead.normalized()) - p.yaw)
        looks[uid] = Look(SystemClock.elapsedRealtime(), target)
        v.playReaction()
        onCall?.invoke(p.species)
        LifeLog.i(String.format(Locale.US, "REACT %s#%d turn=%.0f deg clip=%s", p.speciesId, uid, Math.toDegrees(target.toDouble()), p.species.model.reactClip ?: "none"))
    }

    // ---- thumbstick turn (v0.1.1, device feedback "加个 joystick X rotate animal")

    /**
     * Per frame: the stick's X turns one animal about its vertical axis (through the middle of its length).
     * The turn lives in the view ([AnimalView.stickYaw]) while the stick is held and reaches the store once,
     * when the turn has come to rest, so the guide does not recompose every frame.
     */
    private fun stepStick(now: Long) {
        // wall-clock step (the emulator idles at 2-4 fps); capped so that a long stall cannot spin the animal
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.5f)
        lastFrameMs = now
        val x = if (now < debugStickUntilMs) debugStick else controller.stick(now)
        val target = if (state.phase == Phase.ROOM && state.placing == null) RoomRules.stickTurnRate(x) else 0f
        if (stickUid == null) {
            if (target == 0f) return
            val next = stickTarget() ?: return
            // an animal being dragged does not start turning (the drag committed its turn)
            if (state.draggingUid == next || drag?.uid == next) return
            stickUid = next
            stickRate = 0f
            LifeLog.i(String.format(Locale.US, "STICK start uid=%d x=%.2f", stickUid, x))
        }
        val uid = stickUid ?: return
        val v = views[uid]
        if (v == null || state.animal(uid) == null) {
            stickUid = null
            stickRate = 0f
            return
        }
        if (state.draggingUid == uid || drag?.uid == uid) {
            commitStick(uid, v)
            return
        }
        stickRate += (target - stickRate) * (dt / RoomRules.STICK_SMOOTH_S).coerceAtMost(1f)
        v.stickYaw += stickRate * dt
        if (target == 0f && abs(stickRate) < STICK_REST_RAD_S) commitStick(uid, v)
    }

    private fun commitStick(uid: Int, v: AnimalView) {
        stickUid = null
        stickRate = 0f
        val p = state.animal(uid) ?: return
        val delta = v.uncommittedStick
        LifeLog.i(String.format(Locale.US, "STICK end %s#%d turned=%.0f deg", p.speciesId, uid, Math.toDegrees(delta.toDouble())))
        if (abs(delta) < 1e-4f) return
        v.stickCommitted(p.yaw, delta)
        CreatureStore.turnedBy(uid, delta)
    }

    /** The animal whose card is open, else the one nearest to where the viewer looks. */
    private fun stickTarget(): Int? {
        state.animal(state.cardUid)?.let { if (views[it.uid]?.calibrated == true) return it.uid }
        val f = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
        return state.animals
            .filter { views[it.uid]?.calibrated == true }
            .maxByOrNull { p ->
                val to = Vec3(p.center.x - head.position.x, 0f, p.center.z - head.position.z)
                if (to.length < 1e-3f) 1f else (to.x * f.x + to.z * f.z) / to.length
            }?.uid
    }

    override fun stick(x: Float, ms: Long) {
        debugStick = x
        debugStickUntilMs = SystemClock.elapsedRealtime() + ms
    }

    // ---- the "you" figure (design youSilhouette)

    private fun placeYou() {
        val y = you ?: return
        val room = state.room
        val near = nearestAnimal()
        if (near == null || room == null) {
            if (y.enabled) y.enabled = false
            return
        }
        val spot = RoomRules.youSpot(near.first.copy(center = near.second.dragCenter ?: near.first.center, yaw = near.first.yaw + near.second.stickYaw), state.animals)
        val h = state.youHeightM
        val to = Vec3(head.position.x - spot.x, 0f, head.position.z - spot.z)
        val yaw = if (to.length < 1e-3f) 0f else atan2(to.x, to.z)
        y.setLocal(position = Vec3(spot.x, room.floorY + h / 2f, spot.z), rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(h * YOU_ASPECT, h, 1f))
        if (!y.enabled) y.enabled = true
        youSpot = Vec3(spot.x, room.floorY, spot.z)
    }

    private var youSpot: Vec3? = null

    // ---- the placing ghost (design ghost: ray channel only)

    private fun placeGhost() {
        val g = ghost ?: return
        val room = state.room
        val id = state.placing
        if (id == null || room == null) {
            g.hide()
            return
        }
        val point =
            debugGhost
                ?: controller.sample()?.let { ray ->
                    if (ray.dir.y > -0.02f) null
                    else {
                        val t = (room.floorY - ray.origin.y) / ray.dir.y
                        if (t <= 0f || t > 25f) null else ray.origin + ray.dir * t
                    }
                }
        if (point == null) {
            g.hide()
            return
        }
        val species = Roster[id] ?: return
        val (center, yaw) = RoomRules.place(species, point, head.position, room.floorY, state.animals.filter { it.speciesId != id })
        g.show(species, Vec3(center.x, room.floorY, center.z), yaw)
    }

    // ---- panels

    private fun placePanels() {
        val rig = rig ?: return
        val room = state.room ?: return
        for (i in 0 until 2) {
            val p = state.animals.getOrNull(i) ?: continue
            val v = views[p.uid] ?: continue
            val center = v.dragCenter ?: p.center
            val fit = state.fit(p)
            val viewDir = Vec3(center.x - head.position.x, 0f, center.z - head.position.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
            val tagAt =
                if (fit.overHighM != null && p.scaleN == 1 && room.ceilingY != null) {
                    // taller than the room: under the ceiling line, beside the animal on the viewer's side
                    Vec3(center.x, room.ceilingY - RoomRules.TAG_ABOVE_M - SizeTagHeight.value * DP_M / 2f, center.z) - viewDir * (p.species.widthM / 2f + 0.3f)
                } else {
                    Vec3(center.x, room.floorY + v.topOf(p) * v.appear.coerceAtLeast(0.2f) + RoomRules.TAG_ABOVE_M + SizeTagHeight.value * DP_M / 2f, center.z)
                }
            rig[PanelIds.tag(i)]?.let { scaled(it, tagAt) }
            val readAt = Vec3(center.x, room.floorY + READ_LIFT_M, center.z) - viewDir * READ_TOWARD_M
            rig[PanelIds.read(i)]?.let { scaled(it, readAt) }
        }
        youSpot?.let { s -> rig[PanelIds.YOU]?.let { scaled(it, Vec3(s.x, room.floorY + YOU_LABEL_LIFT_M, s.z) + towardHead(s) * 0.12f) } }
    }

    private fun towardHead(p: Vec3): Vec3 = Vec3(head.position.x - p.x, 0f, head.position.z - p.z).let { if (it.length < 1e-3f) Vec3.ZERO else it.normalized() }

    /** Panels on objects keep their visual angle: scale with the viewing distance (0.45 - 6 m, Spatial Ruler rule). */
    private fun scaled(slot: PanelRig.Slot, at: Vec3) {
        // placement only: whether a panel shows is decided in render()
        if (!slot.shown) return
        val d = (at - head.position).length.coerceIn(0.45f, 6f)
        if (abs(slot.scaleX - d) > 0.01f) slot.show(at = at, kx = d, ky = d) else slot.moveTo(at)
    }

    // =========================================================================================
    // input
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        LifeLog.i("TAP target=$name kind=${value.interactionKind} stage=${LifeLog.pos(stage)}")
        if (now - lastDragEndMs < TAP_AFTER_DRAG_MS) return
        when {
            name == "floor" && stage != null -> {
                val room = state.room ?: return
                CreatureStore.floorTapped(Vec3(stage.x, room.floorY, stage.z))
            }
            name.startsWith("animal:") -> name.removePrefix("animal:").toIntOrNull()?.let { CreatureStore.animalTapped(it) }
        }
    }

    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
        when (phase) {
            "start" -> {
                val uid = name.removePrefix("animal:").toIntOrNull()
                val p = if (name.startsWith("animal:")) state.animal(uid) else null
                if (p == null || state.placing != null) {
                    drag = null
                    return
                }
                val grab = Vec3(p.center.x, (state.room?.floorY ?: 0f) + p.species.topM / p.scaleN * 0.5f, p.center.z)
                drag = Drag(p.uid, grab, p.center)
                LifeLog.i("DRAG start uid=${p.uid} kind=${value.interactionKind}")
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
            }
            else -> endDrag(phase == "cancel")
        }
    }

    /**
     * I4: the animal slides on the floor by the hand's horizontal travel, geared by its distance
     * (distance / 1.5 m, 1 - 4 x), and stays on the floor outline. Not the Spatial Ruler pointing-line rule:
     * the grab point of an animal is near eye height, where that line runs almost level and its floor
     * crossing flies off (a 0.75 m cursor move threw a horse 6 m on the emulator). Logged as a deviation.
     */
    private fun stepDrag() {
        val d = drag ?: return
        val room = state.room ?: return
        val hand = handDelta(d.totalPx) ?: return
        if (!d.moved) {
            if (hand.length < RoomRules.DRAG_START_M) return
            d.moved = true
            CreatureStore.dragStarted(d.uid)
            LifeLog.i("DRAG moving uid=${d.uid}")
        }
        val v = views[d.uid] ?: return
        val dist = Vec3(d.start.x - head.position.x, 0f, d.start.z - head.position.z).length
        val gain = (dist / DRAG_GAIN_M).coerceIn(1f, 4f)
        // vertical hand travel (up = away) counts as depth, so a flat-on-screen drag can also push it back
        val f = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
        val flat = Vec3(hand.x, 0f, hand.z) + f * hand.y
        val c = Vec3(d.start.x + flat.x * gain, room.floorY, d.start.z + flat.z * gain)
        if (RoomRules.insideFloor(room.floor, d.start) && !RoomRules.insideFloor(room.floor, c)) return
        d.center = c
        v.dragCenter = c
    }

    private fun endDrag(cancelled: Boolean) {
        val d = drag ?: return
        drag = null
        views[d.uid]?.dragCenter = null
        if (d.moved) {
            lastDragEndMs = SystemClock.elapsedRealtime()
            LifeLog.i("DRAG end uid=${d.uid} center=${LifeLog.pos(d.center)} cancelled=$cancelled")
            CreatureStore.dragEnded(d.uid, if (cancelled) null else d.center)
        }
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug (Probe.Host)
    // =========================================================================================

    override fun sceneLines(what: String): List<String> =
        when (what) {
            "pose" -> listOf("POSE hmd pos=${LifeLog.pos(head.position)} fwd=${LifeLog.pos(head.forward)} valid=$hasHead support=${headTracker.supportState()} frames=$frames")
            "panels" -> rig?.dump() ?: emptyList()
            else ->
                state.animals.map { p ->
                    val v = views[p.uid]
                    val fit = state.fit(p)
                    String.format(
                        Locale.US,
                        "ANIMAL %s#%d center=%s yaw=%.0f n=%d model=%s top=%.2f overHigh=%s overLong=%s look=%.0f",
                        p.speciesId, p.uid, LifeLog.pos(p.center), Math.toDegrees(p.yaw.toDouble()), p.scaleN, v?.hasModel, p.species.topM / p.scaleN,
                        fit.overHighM?.let { String.format(Locale.US, "%.2f", it) }, fit.overLongM?.let { String.format(Locale.US, "%.2f", it) },
                        Math.toDegrees((v?.lookYaw ?: 0f).toDouble()),
                    ) + " box=" + (stageRoot?.let { v?.boundsIn(it) })
                }
        }

    override fun tapFloor(x: Float, z: Float) {
        val room = state.room ?: return
        CreatureStore.floorTapped(Vec3(x, room.floorY, z))
    }

    override fun floorAhead(aheadM: Float, sideM: Float): Pair<Float, Float>? {
        if (CreatureStore.state.value.room == null || !hasHead) return null
        val f = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
        val right = Vec3(-f.z, 0f, f.x)
        val p = head.position + f * aheadM + right * sideM
        return p.x to p.z
    }

    override fun drag(uid: Int, dx: Float, dz: Float, ms: Long) {
        val p = state.animal(uid) ?: return
        val v = views[uid] ?: return
        CreatureStore.dragStarted(uid)
        val t0 = SystemClock.elapsedRealtime()
        val tick =
            object : Runnable {
                override fun run() {
                    val q = ((SystemClock.elapsedRealtime() - t0) / ms.toFloat()).coerceIn(0f, 1f)
                    v.dragCenter = p.center + Vec3(dx * q, 0f, dz * q)
                    if (q < 1f || CreatureStore.frozen) main.postDelayed(this, 50L)
                    else {
                        v.dragCenter = null
                        CreatureStore.dragEnded(uid, p.center + Vec3(dx, 0f, dz))
                    }
                }
            }
        main.post(tick)
    }

    override fun ghostAt(x: Float, z: Float) {
        debugGhost = CreatureStore.state.value.room?.let { Vec3(x, it.floorY, z) }
    }

    fun clearDebugGhost() {
        debugGhost = null
    }

    companion object {
        private const val REBIND_DELAY_MS = 250L
        private const val HEAD_SAMPLE_MS = 200L
        private const val LOG_MS = 5_000L
        private const val SLOW_LOAD_MS = 6_000L
        private const val APPEAR_MS = 400f
        private const val FADE_MS = 300f
        private const val CATCHER_M = 30f
        private const val CATCHER_THICK_M = 0.02f
        private const val REPLACE_M = 0.3f
        private const val TAP_AFTER_DRAG_MS = 400L
        private const val STICK_REST_RAD_S = 0.02f
        private const val DRAG_GAIN_M = 1.5f
        private const val READ_LIFT_M = 0.09f
        private const val READ_TOWARD_M = 0.06f
        private const val YOU_LABEL_LIFT_M = 0.06f
        private const val YOU_ASPECT = 0.34f
        private const val CARD_CLEAR_RAD = 0.42f // 24 degrees: half the guide window plus half the card

        /** Info card 480 dp wide = 0.38 m (design physicalM). */
        val CARD_SCALE = 0.38f / (InfoCardWidth.value * DP_M)

        private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

        private fun easeInOut(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
        }

        /** The "you" figure: youCyan 35 % fill, 80 % outline, 1 : 2.94 (width : height). */
        private fun youBitmap(): Bitmap {
            val w = 300
            val h = 880
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cyan = LifeColors.YouCyan.toArgb()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cyan
                alpha = (255 * 0.35f).toInt()
                style = Paint.Style.FILL
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cyan
                alpha = (255 * 0.8f).toInt()
                style = Paint.Style.STROKE
                strokeWidth = 9f
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            val cx = w / 2f
            path.addCircle(cx, 70f, 58f, Path.Direction.CW)
            path.addRoundRect(RectF(cx - 92f, 140f, cx + 92f, 480f), 60f, 60f, Path.Direction.CW)
            path.addRoundRect(RectF(cx - 140f, 150f, cx - 100f, 470f), 20f, 20f, Path.Direction.CW)
            path.addRoundRect(RectF(cx + 100f, 150f, cx + 140f, 470f), 20f, 20f, Path.Direction.CW)
            path.addRoundRect(RectF(cx - 82f, 430f, cx - 8f, h - 8f), 30f, 30f, Path.Direction.CW)
            path.addRoundRect(RectF(cx + 8f, 430f, cx + 82f, h - 8f), 30f, 30f, Path.Direction.CW)
            path.op(Path(), Path.Op.UNION)
            c.drawPath(path, fill)
            c.drawPath(path, line)
            return bmp
        }
    }
}

/**
 * The placing ghost (design `ghost`): a see-through footprint (whole body x width), a preview tape and an
 * arrow at the snout, following the controller ray on the floor while a species is being placed.
 */
internal class Ghost(parent: Entity, kit: SceneKit) {
    private val root = Entity().named("ghost")
    private val fill = kit.planeEntity(kit.ghostFill, "ghost:fill")
    private val tape = kit.boxEntity(kit.ghostEdge, "ghost:tape")
    private val arrow = com.pico.spatial.core.ecs.ModelEntity(kit.cone, kit.ghostEdge).named("ghost:arrow")
    private var on = true

    init {
        parent.addChild(root)
        root.addChild(fill)
        root.addChild(tape)
        root.addChild(arrow)
        hide()
    }

    fun show(s: Species, center: Vec3, yaw: Float) {
        root.setLocal(position = center, rotation = Quat4.axisAngle(Vec3.UP, yaw))
        // the plane lies in XY facing +Z: lay it flat (facing up)
        fill.setLocal(
            position = Vec3(s.lengthM / 2f - s.fullLengthM / 2f, 0.006f, 0f),
            rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()),
            scale = Vec3(s.fullLengthM, s.widthM, 1f),
        )
        tape.setLocal(position = Vec3(0f, 0.008f, 0f), scale = Vec3(s.lengthM, 0.004f, 0.025f))
        // the cone points along +Y: turn it to +X, lying on the floor beyond the snout
        val a = (0.12f + s.widthM * 0.08f).coerceAtMost(0.4f)
        arrow.setLocal(position = Vec3(s.lengthM / 2f + a * 0.7f, 0.02f, 0f), rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), -(PI / 2).toFloat()), scale = Vec3(a, a, 0.02f / 0.5f * a))
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    fun hide() {
        if (on) {
            root.enabled = false
            on = false
        }
    }
}
