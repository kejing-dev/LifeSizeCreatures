package io.embodit.lifesize.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.lifesize.domain.RoomGeometry
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog
import java.util.Locale

/**
 * Floor and ceiling from plane detection (ported from Spatial Ruler's PlaneTracker; Full Space only).
 * Planes arrive progressively and keep being re-reported; only FLOOR and CEILING matter here: the floor
 * height and outline (the largest FLOOR plane) and the lowest CEILING plane.
 *
 * Vertices are converted to Stage metres here (`planeOrientation` / `boundingBoxSize` of the SDK anchor
 * are unreliable on the emulator). Main thread only.
 */
class PlaneTracker {

    fun interface Listener {
        fun onRoom(room: RoomGeometry)
    }

    private class Plane(val semantic: String, val vertices: List<Vec3>) {
        val meanY: Float = vertices.map { it.y }.average().toFloat()
        val area: Float =
            run {
                var a = 0f
                for (i in vertices.indices) {
                    val p = vertices[i]
                    val q = vertices[(i + 1) % vertices.size]
                    a += p.x * q.z - q.x * p.z
                }
                kotlin.math.abs(a) / 2f
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val planes = LinkedHashMap<String, Plane>()
    private var subscription: Cancellable? = null
    private var listener: Listener? = null
    private var last: RoomGeometry? = null

    val room: RoomGeometry?
        get() = last

    fun start(listener: Listener) {
        if (subscription != null) return
        this.listener = listener
        // subscribe first, then start: no event is lost
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        val started = runCatching { PlaneTrackingManager.start() }
        started.exceptionOrNull()?.let { LifeLog.e("PLANES start failed: $it", it) }
        LifeLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        listener = null
        planes.clear()
        last = null
        LifeLog.i("PLANES tracking stopped")
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val semantic = runCatching { anchor.semantics.name }.getOrNull() ?: return
                if (semantic != "FLOOR" && semantic != "CEILING") return
                val vertices = runCatching { anchor.stageVertices() }.getOrNull() ?: return
                if (vertices.size < 3) return
                val previous = planes[id]
                if (previous != null && sameGeometry(previous.vertices, vertices)) return
                planes[id] = Plane(semantic, vertices)
                publish()
            }
            AnchorUpdate.Event.REMOVED -> if (planes.remove(id) != null) publish()
            else -> Unit
        }
    }

    private fun publish() {
        val floor = planes.values.filter { it.semantic == "FLOOR" }.maxByOrNull { it.area } ?: return
        val ceiling = planes.values.filter { it.semantic == "CEILING" && it.meanY > floor.meanY + 0.5f }.minByOrNull { it.meanY }
        val room = RoomGeometry(floor.meanY, ceiling?.meanY, floor.vertices)
        val prev = last
        last = room
        if (prev == null || prev.floorY != room.floorY || prev.ceilingY != room.ceilingY || prev.floor.size != room.floor.size) {
            LifeLog.i(
                String.format(
                    Locale.US,
                    "PLANES room floorY=%.3f ceilingY=%s floorPts=%d planes=%d",
                    room.floorY,
                    room.ceilingY?.let { String.format(Locale.US, "%.3f", it) } ?: "none",
                    room.floor.size,
                    planes.size,
                )
            )
        }
        listener?.onRoom(room)
    }

    private fun PlaneAnchor.stageVertices(): List<Vec3> {
        val q = transform.quaternion
        val p = transform.position
        return vertices.map { v ->
            val w = q.rotateVector(v) + p
            Vec3(w.x, w.y, w.z)
        }
    }

    /** The emulator re-reports unchanged planes all the time; those updates are dropped. */
    private fun sameGeometry(a: List<Vec3>, b: List<Vec3>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if ((a[i] - b[i]).length > 0.0005f) return false
        return true
    }
}
