package io.embodit.lifesize.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.lifesize.domain.Copy
import io.embodit.lifesize.domain.Fit
import io.embodit.lifesize.domain.HeadPose
import io.embodit.lifesize.domain.Lang
import io.embodit.lifesize.domain.Phase
import io.embodit.lifesize.domain.Placed
import io.embodit.lifesize.domain.RoomGeometry
import io.embodit.lifesize.domain.RoomRules
import io.embodit.lifesize.domain.Roster
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A message that the hint line shows for a while (or until the next action). */
data class Flash(val kind: Kind, val name: String = "") {
    enum class Kind { ENTER_FAILED, LOAD_FAILED, REPLACED }
}

/** The whole app state, shared by the field guide window and the room Stage (one process). */
data class CreatureState(
    val phase: Phase = Phase.SHARED,
    val lang: Lang = Lang.ZH,
    val animals: List<Placed> = emptyList(),
    /** S3: the species being placed. */
    val placing: String? = null,
    /** Chosen in the Shared Space; placed as soon as the floor is known (I14). */
    val pending: String? = null,
    /** S5: the animal whose info card is open (at most one card). */
    val cardUid: Int? = null,
    /** S9: the animal being dragged. */
    val draggingUid: Int? = null,
    val room: RoomGeometry? = null,
    val youHeightM: Float = 1.68f,
    val flash: Flash? = null,
    val nextUid: Int = 1,
) {
    val inFullSpace: Boolean
        get() = phase != Phase.SHARED && phase != Phase.ENTERING

    /** S2 / S4 / S8 by count; the other phases by themselves. */
    val roomStateId: String
        get() =
            if (phase != Phase.ROOM) phase.stateId
            else when (animals.size) {
                0 -> "S2"
                1 -> "S4"
                else -> "S8"
            }

    fun fit(p: Placed): Fit = RoomRules.fit(p, room)

    fun animal(uid: Int?): Placed? = animals.firstOrNull { it.uid == uid }

    val tilesEnabled: Boolean
        get() = phase == Phase.SHARED || phase == Phase.ROOM
}

/** The hint line (design `stateModel.hintPriority`). */
data class Hint(val text: String, val kind: Kind) {
    enum class Kind { NORMAL, PROGRESS, ERROR }
}

fun CreatureState.hint(): Hint {
    val c = Copy(lang)
    fun n(t: String) = Hint(t, Hint.Kind.NORMAL)
    return when (phase) {
        Phase.SHARED -> if (flash?.kind == Flash.Kind.ENTER_FAILED) Hint(c.hintEnterFailed, Hint.Kind.ERROR) else n(c.hintShared)
        Phase.ENTERING -> Hint(c.hintEntering, Hint.Kind.PROGRESS)
        Phase.NO_FLOOR -> Hint(c.hintNoFloor, Hint.Kind.ERROR)
        Phase.FINDING -> Hint(c.hintFinding, Hint.Kind.PROGRESS)
        Phase.ROOM ->
            when {
                animals.any { it.loading } -> Hint(c.hintLoading, Hint.Kind.PROGRESS)
                flash?.kind == Flash.Kind.LOAD_FAILED -> Hint(c.hintLoadFailed, Hint.Kind.ERROR)
                placing != null -> n(c.hintPlacing)
                draggingUid != null -> n(c.hintDragging)
                flash?.kind == Flash.Kind.REPLACED -> n(c.hintReplaced(flash.name))
                animals.any { it.scaleN > 1 } -> n(c.hintScaled)
                animals.any { fit(it).tooBig } -> n(c.hintTooBig)
                cardUid != null -> n(c.hintOne)
                animals.size >= 2 -> n(c.hintTwo)
                animals.size == 1 -> n(c.hintOne)
                else -> n(c.hintEmpty)
            }
    }
}

/**
 * The one state holder of the app (main thread only). The guide window and the room Stage are two
 * containers of one process: both read [state] and send events here. The window lends the Stage
 * navigator ([stageHost]); the room scene lends the head pose and the model cache ([sceneHost]).
 *
 * Per-frame 3D work never goes through here: the scene moves a dragged animal itself and reports only
 * the start and the end of the drag; the head height arrives at most a few times a second.
 */
object CreatureStore {
    interface StageHost {
        fun openStage()

        fun closeStage()
    }

    interface SceneHost {
        fun head(): HeadPose?

        fun isModelReady(speciesId: String): Boolean

        fun requestModel(speciesId: String)

        /** I5: turn towards the viewer, play the reaction and the call. */
        fun react(uid: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CreatureState(lang = if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN))
    val state: StateFlow<CreatureState> = _state.asStateFlow()

    var stageHost: StageHost? = null
    var sceneHost: SceneHost? = null

    /** Debug `freeze=1`: timed changes (look-back, the 3 s hint) wait, so that 2 - 4 fps captures catch them. */
    @Volatile var frozen = false

    private val heights = ArrayDeque<Pair<Long, Float>>()

    private fun set(next: CreatureState) {
        val prev = _state.value
        if (next == prev) return
        _state.value = next
        if (next.roomStateId != prev.roomStateId || next.placing != prev.placing || next.cardUid != prev.cardUid || next.animals.size != prev.animals.size) {
            LifeLog.i("STATE ${prev.roomStateId}->${next.roomStateId} placing=${next.placing} card=${next.cardUid} animals=${next.animals.joinToString { "${it.speciesId}#${it.uid}${if (it.scaleN > 1) "/1:${it.scaleN}" else ""}${if (it.loading) "(loading)" else ""}" }}")
        }
    }

    private inline fun update(f: (CreatureState) -> CreatureState) = set(f(_state.value))

    // =========================================================================================
    // guide window
    // =========================================================================================

    fun tileClicked(id: String) {
        val s = _state.value
        if (Roster[id] == null) return
        when (s.phase) {
            Phase.SHARED -> {
                LifeLog.i("STAGE open requested for=$id")
                set(s.copy(phase = Phase.ENTERING, pending = id, flash = null))
                val host = stageHost
                if (host == null) stageResult(false, "no window") else host.openStage()
            }
            Phase.ROOM -> {
                // I3: tapping the tile that is being placed cancels; any other tile (placed or not) places that one
                set(s.copy(placing = if (s.placing == id) null else id, cardUid = null, flash = if (s.flash?.kind == Flash.Kind.LOAD_FAILED) null else s.flash))
            }
            else -> Unit
        }
    }

    fun exitClicked() {
        LifeLog.i("STAGE close requested")
        stageHost?.closeStage()
        stageClosed()
    }

    fun clearClicked() = update { it.copy(animals = emptyList(), cardUid = null, placing = null, draggingUid = null, flash = null) }

    fun langSelected(lang: Lang) = update { it.copy(lang = lang) }

    fun retryClicked() {
        if (_state.value.phase != Phase.NO_FLOOR) return
        update { it.copy(phase = Phase.FINDING) }
        armFloorTimeout()
    }

    // =========================================================================================
    // Stage lifecycle
    // =========================================================================================

    fun stageResult(allowed: Boolean, detail: String) {
        LifeLog.i("STAGE open result allowed=$allowed $detail")
        val s = _state.value
        if (s.phase != Phase.ENTERING) return
        if (!allowed) {
            set(s.copy(phase = Phase.SHARED, pending = null, flash = Flash(Flash.Kind.ENTER_FAILED)))
            return
        }
        if (s.room != null) set(s.copy(phase = Phase.ROOM, placing = s.pending, pending = null))
        else {
            set(s.copy(phase = Phase.FINDING))
            armFloorTimeout()
        }
    }

    /** The Stage went away (exit key, system exit, background): everything in the room is put away. */
    fun stageClosed() {
        main.removeCallbacks(floorTimeout)
        heights.clear()
        val s = _state.value
        set(CreatureState(lang = s.lang, youHeightM = s.youHeightM, nextUid = s.nextUid))
    }

    private val floorTimeout =
        Runnable {
            val s = _state.value
            if (s.phase == Phase.FINDING && s.room == null) {
                LifeLog.i("PLANES no floor after ${RoomRules.FLOOR_TIMEOUT_MS} ms")
                set(s.copy(phase = Phase.NO_FLOOR))
            }
        }

    private fun armFloorTimeout() {
        main.removeCallbacks(floorTimeout)
        main.postDelayed(floorTimeout, RoomRules.FLOOR_TIMEOUT_MS)
    }

    fun roomUpdated(room: RoomGeometry) {
        val s = _state.value
        if (!s.inFullSpace && s.phase != Phase.ENTERING) return
        if (s.phase == Phase.FINDING || s.phase == Phase.NO_FLOOR) {
            main.removeCallbacks(floorTimeout)
            set(s.copy(phase = Phase.ROOM, room = room, placing = s.pending, pending = null))
        } else {
            set(s.copy(room = room))
        }
    }

    /** Head height above the floor, sampled a few times a second by the scene (I11). */
    fun headSample(aboveFloorM: Float) {
        val now = SystemClock.elapsedRealtime()
        heights.addLast(now to aboveFloorM)
        while (heights.isNotEmpty() && now - heights.first.first > 10_000L) heights.removeFirst()
        val h = RoomRules.youHeight(heights.maxOf { it.second })
        val s = _state.value
        if (kotlin.math.abs(h - s.youHeightM) >= 0.01f) set(s.copy(youHeightM = h))
    }

    // =========================================================================================
    // room
    // =========================================================================================

    /** I2: the floor catcher was tapped while a species is being placed. [point] is on the floor. */
    fun floorTapped(point: Vec3) {
        val s = _state.value
        val id = s.placing ?: return
        val room = s.room ?: return
        val species = Roster[id] ?: return
        val head = sceneHost?.head()?.position ?: Vec3(0f, room.floorY + 1.6f, 0f)
        val existing = s.animals.firstOrNull { it.speciesId == id }
        var animals = s.animals
        var flash = s.flash?.takeIf { it.kind == Flash.Kind.REPLACED }
        val others = animals.filter { it.uid != existing?.uid }
        val (center, yaw) = RoomRules.place(species, point, head, room.floorY, others)
        LifeLog.i(String.format(Locale.US, "PLACE %s tap=(%.2f, %.2f) -> center=(%.2f, %.2f) yaw=%.0f", id, point.x, point.z, center.x, center.z, Math.toDegrees(yaw.toDouble())))
        var next = s.nextUid
        if (existing != null) {
            // I3: placing an animal that is already here moves it; the count does not change
            animals = animals.map { if (it.uid == existing.uid) it.copy(center = center, yaw = yaw, scaleN = 1) else it }
        } else {
            if (animals.size >= RoomRules.MAX_ANIMALS) {
                // I9: the first one goes back to the guide
                val gone = animals.first()
                animals = animals.drop(1)
                flash = Flash(Flash.Kind.REPLACED, gone.species.name(s.lang))
                main.removeCallbacks(clearFlash)
                main.postDelayed(clearFlash, RoomRules.FLASH_MS)
            }
            val ready = sceneHost?.isModelReady(id) == true
            animals = animals + Placed(next, id, center, yaw, loading = !ready)
            next++
            if (!ready) sceneHost?.requestModel(id)
        }
        set(s.copy(animals = animals, placing = null, nextUid = next, cardUid = null, flash = flash))
    }

    private val clearFlash: Runnable =
        object : Runnable {
            override fun run() {
                if (frozen) {
                    main.postDelayed(this, 500L)
                    return
                }
                update { if (it.flash?.kind == Flash.Kind.REPLACED) it.copy(flash = null) else it }
            }
        }

    fun modelReady(speciesId: String) = update { s -> s.copy(animals = s.animals.map { if (it.speciesId == speciesId) it.copy(loading = false) else it }) }

    fun modelFailed(speciesId: String) {
        LifeLog.w("MODEL failed $speciesId")
        update { s -> s.copy(animals = s.animals.filterNot { it.speciesId == speciesId && it.loading }, flash = Flash(Flash.Kind.LOAD_FAILED)) }
    }

    fun animalTapped(uid: Int) {
        val s = _state.value
        if (s.placing != null || s.animal(uid) == null) return
        LifeLog.i("CARD open uid=$uid")
        set(s.copy(cardUid = uid))
        sceneHost?.react(uid)
    }

    fun cardClosed() = update { it.copy(cardUid = null) }

    fun rotateClicked() =
        update { s ->
            val uid = s.cardUid ?: return@update s
            s.copy(animals = s.animals.map { if (it.uid == uid) it.copy(yaw = it.yaw + (Math.PI / 2).toFloat()) else it })
        }

    fun sendBackClicked() =
        update { s ->
            val uid = s.cardUid ?: return@update s
            s.copy(animals = s.animals.filterNot { it.uid == uid }, cardUid = null)
        }

    /** I7: true size, or 1:n moved onto the floor. */
    fun scaleSelected(fitRoom: Boolean) =
        update { s ->
            val uid = s.cardUid ?: return@update s
            s.copy(
                animals =
                    s.animals.map { p ->
                        if (p.uid != uid) p
                        else if (!fitRoom) p.copy(scaleN = 1)
                        else {
                            val n = s.fit(p.copy(scaleN = 1)).n
                            p.copy(scaleN = n, center = RoomRules.moveIntoFloor(p, n, s.room?.floor ?: emptyList()))
                        }
                    }
            )
        }

    fun dragStarted(uid: Int) = update { if (it.animal(uid) != null && it.placing == null) it.copy(draggingUid = uid) else it }

    fun dragEnded(uid: Int, center: Vec3?) =
        update { s ->
            s.copy(
                draggingUid = null,
                animals = if (center == null) s.animals else s.animals.map { if (it.uid == uid) it.copy(center = center) else it },
            )
        }

    // =========================================================================================
    // debug (DebugCommands in the debug build)
    // =========================================================================================

    fun debugSet(f: (CreatureState) -> CreatureState) = update(f)

    fun debugPlaceAt(id: String, point: Vec3) {
        val s = _state.value
        if (s.phase != Phase.ROOM) return
        set(s.copy(placing = id))
        floorTapped(point)
    }
}
