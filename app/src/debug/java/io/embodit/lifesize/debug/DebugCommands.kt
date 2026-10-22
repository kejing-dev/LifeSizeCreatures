package io.embodit.lifesize.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.lifesize.app.CreatureStore
import io.embodit.lifesize.app.hint
import io.embodit.lifesize.domain.Lang
import io.embodit.lifesize.domain.Phase
import io.embodit.lifesize.domain.Roster
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog
import io.embodit.lifesize.ui.room.RoomScene
import io.embodit.lifesize.ui.room.debug.Probe

/**
 * Debug commands (design debugHooks). Every command logs `CMD ok ...` or `CMD fail ...`; `dump` prints
 * the state in one `DUMP` line plus `ANIMAL` lines. Placing commands open the Full Space first when needed.
 *
 *   room S0|S13|S1|S2|S4|S8|S10   jump to a room state (S4 = horse, S8 = shark + shiba; S13 freezes)
 *   place <id>[,ahead[,side]]     place an animal on the floor ahead of the viewer (default 2.5 m)
 *   tile <id> | exit | clear | retry | lang zh|en
 *   card <i> | close | rotate | sendback | scale fit|true      the info card of the i-th animal (0 / 1)
 *   ceiling <m>                   pretend the net ceiling height is m metres
 *   height <m>                    the "you" height (override until the next head sample changes it)
 *   slowload 1|0                  models take 6 s more (S12)
 *   freeze 1|0                    timed changes wait (look-back, the 3 s hint, a debug drag's release)
 *   drag <i>[,dx,dz,ms]           drag the i-th animal (default 0.8 m along x over 3 s; S9)
 *   ghost <ahead> | ghostoff      the placing ghost as if a controller ray hit the floor ahead
 *   dump | pose | panels
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(cmd: String, arg: String?) {
        val ok = runCatching { exec(cmd, arg) }.onFailure { LifeLog.e("CMD fail $cmd: $it", it) }.getOrDefault(false)
        LifeLog.i("CMD ${if (ok) "ok" else "fail"} $cmd ${arg ?: ""}")
    }

    private fun s() = CreatureStore.state.value

    private fun exec(cmd: String, arg: String?): Boolean {
        val parts = arg?.split(',')?.map { it.trim() } ?: emptyList()
        val first = parts.firstOrNull()
        val nums = parts.drop(1).mapNotNull { it.toFloatOrNull() }
        when (cmd) {
            "dump" -> dump()
            "pose" -> Probe.host?.sceneLines("pose")?.forEach { LifeLog.i(it) }
            "panels" -> Probe.host?.sceneLines("panels")?.forEach { LifeLog.i(it) }
            "tile" -> CreatureStore.tileClicked(first ?: return false)
            "exit" -> CreatureStore.exitClicked()
            "clear" -> CreatureStore.clearClicked()
            "retry" -> CreatureStore.retryClicked()
            "lang" -> CreatureStore.langSelected(if (first == "en") Lang.EN else Lang.ZH)
            "close" -> CreatureStore.cardClosed()
            "rotate" -> CreatureStore.rotateClicked()
            "sendback" -> CreatureStore.sendBackClicked()
            "scale" -> CreatureStore.scaleSelected(fitRoom = first == "fit")
            "card" -> {
                val p = s().animals.getOrNull(first?.toIntOrNull() ?: 0) ?: return false
                CreatureStore.animalTapped(p.uid)
            }
            "freeze" -> CreatureStore.frozen = first == "1"
            "slowload" -> Probe.slowLoad = first == "1"
            "height" -> {
                val m = first?.toFloatOrNull() ?: return false
                CreatureStore.debugSet { it.copy(youHeightM = m) }
            }
            "ceiling" -> {
                val m = first?.toFloatOrNull() ?: return false
                CreatureStore.debugSet { st -> st.room?.let { st.copy(room = it.copy(ceilingY = it.floorY + m)) } ?: st }
            }
            "place" -> place(first ?: return false, nums.getOrNull(0) ?: 2.5f, nums.getOrNull(1) ?: 0f)
            "drag" -> {
                val p = s().animals.getOrNull(first?.toIntOrNull() ?: 0) ?: return false
                val host = Probe.host ?: return false
                host.drag(p.uid, nums.getOrNull(0) ?: 0.8f, nums.getOrNull(1) ?: 0f, (nums.getOrNull(2) ?: 3000f).toLong())
            }
            "ghost" -> {
                val host = Probe.host ?: return false
                val (x, z) = host.floorAhead(first?.toFloatOrNull() ?: 2.5f, 0f) ?: return false
                host.ghostAt(x, z)
            }
            "ghostoff" -> (Probe.host as? RoomScene)?.clearDebugGhost()
            "room" -> room(first ?: return false)
            else -> return false
        }
        return true
    }

    private fun room(id: String) {
        when (id) {
            "S0" -> if (s().phase != Phase.SHARED) CreatureStore.exitClicked()
            "S13" -> {
                CreatureStore.frozen = true
                if (s().phase == Phase.SHARED) CreatureStore.debugSet { it.copy(phase = Phase.ENTERING, pending = Roster.all.first().id, flash = null) }
            }
            "S1" -> enterThen { CreatureStore.debugSet { it.copy(phase = Phase.FINDING, animals = emptyList(), placing = null, cardUid = null) } }
            "S10" -> enterThen { CreatureStore.debugSet { it.copy(phase = Phase.NO_FLOOR, animals = emptyList(), placing = null, cardUid = null) } }
            "S2" -> enterThen { CreatureStore.debugSet { it.copy(animals = emptyList(), placing = null, cardUid = null) } }
            "S4" ->
                enterThen {
                    CreatureStore.clearClicked()
                    place("horse", 2.6f, 0f)
                }
            "S8" ->
                enterThen {
                    CreatureStore.clearClicked()
                    place("shark", 4.0f, 0f)
                    main.postDelayed({ place("shiba", 2.0f, -1.6f) }, 600L)
                }
        }
    }

    /** Runs [then] once the room is open and the floor is known; opens the Full Space first if needed. */
    private fun enterThen(then: () -> Unit) {
        // a debug S1 / S10 over a known floor: back to the room first
        if ((s().phase == Phase.FINDING || s().phase == Phase.NO_FLOOR) && s().room != null) CreatureStore.debugSet { it.copy(phase = Phase.ROOM) }
        val st = s()
        if (st.phase == Phase.ROOM && Probe.host?.floorAhead(1f, 0f) != null) {
            then()
            return
        }
        // from S0, or from a debug S13 that never opened the Stage
        if (st.phase == Phase.SHARED || (st.phase == Phase.ENTERING && Probe.host == null)) {
            CreatureStore.frozen = false
            CreatureStore.debugSet { it.copy(phase = Phase.ENTERING, pending = null, flash = null) }
            CreatureStore.stageHost?.openStage()
        }
        val t0 = SystemClock.elapsedRealtime()
        main.postDelayed(
            object : Runnable {
                override fun run() {
                    if (s().phase == Phase.ROOM && Probe.host?.floorAhead(1f, 0f) != null) then()
                    else if (SystemClock.elapsedRealtime() - t0 < 60_000L) main.postDelayed(this, 300L)
                    else LifeLog.w("CMD gave up waiting for the room")
                }
            },
            300L,
        )
    }

    private fun place(id: String, ahead: Float, side: Float) {
        if (Roster[id] == null) return
        enterThen {
            val (x, z) = Probe.host?.floorAhead(ahead, side) ?: return@enterThen
            val room = s().room ?: return@enterThen
            CreatureStore.debugPlaceAt(id, Vec3(x, room.floorY, z))
        }
    }

    private fun dump() {
        val st = s()
        LifeLog.i(
            "DUMP state=${st.roomStateId} phase=${st.phase} lang=${st.lang} placing=${st.placing} card=${st.cardUid} dragging=${st.draggingUid} " +
                "you=${st.youHeightM} floorY=${st.room?.floorY} ceilingY=${st.room?.ceilingY} hint='${st.hint().text}' frozen=${CreatureStore.frozen} slowload=${Probe.slowLoad}"
        )
        Probe.host?.sceneLines("animals")?.forEach { LifeLog.i(it) }
    }
}
