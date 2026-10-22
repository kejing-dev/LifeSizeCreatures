package io.embodit.lifesize.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Lang { ZH, EN }

/**
 * Room layer of the state model (design `stateModel.rule`): S0 / S13 live in the Shared Space, the rest
 * in the Full Space. S2 / S4 / S8 are [ROOM] with 0 / 1 / 2 animals; S3, S5, S6, S7, S9, S12 are flags
 * on top (placing, card, too big, scaled, dragging, loading).
 */
enum class Phase(val stateId: String) {
    SHARED("S0"),
    ENTERING("S13"),
    FINDING("S1"),
    NO_FLOOR("S10"),
    ROOM("S2"),
}

/** One animal in the room. [center] is the middle of the measured length on the floor (Stage metres). */
data class Placed(
    val uid: Int,
    val speciesId: String,
    val center: Vec3,
    /** Rotation about +Y; the snout points along [snoutDir]. */
    val yaw: Float,
    /** 1 = true size; n > 1 = shown 1:n ("装进房间"). */
    val scaleN: Int = 1,
    /** The model is not in the room yet (S12). */
    val loading: Boolean = false,
) {
    val species: Species
        get() = Roster[speciesId]!!

    val snoutDir: Vec3
        get() = snoutDir(yaw)
}

fun snoutDir(yaw: Float): Vec3 = Vec3(cos(yaw), 0f, -sin(yaw))

fun yawOf(dir: Vec3): Float = atan2(-dir.z, dir.x)

/** What the floor and ceiling planes say about the room. */
data class RoomGeometry(
    val floorY: Float,
    /** Lowest CEILING plane; null when none was reported (the height check is then skipped). */
    val ceilingY: Float?,
    /** Outline of the FLOOR plane in Stage metres (y ignored); may be empty. */
    val floor: List<Vec3>,
) {
    val netHeightM: Float?
        get() = ceilingY?.let { it - floorY }
}

/** Result of the room check (I12). */
data class Fit(val overHighM: Float?, val overLongM: Float?, val n: Int) {
    val tooBig: Boolean
        get() = overHighM != null || overLongM != null
}

object RoomRules {
    const val MAX_ANIMALS = 2
    const val YOU_GAP_M = 0.4f
    const val TAG_ABOVE_M = 0.15f
    const val CARD_DISTANCE_M = 1.2f
    const val CARD_LEFT_RAD = 0.43633232f // 25 degrees
    const val FLOOR_TIMEOUT_MS = 20_000L
    const val TURN_MS = 500L
    const val LOOK_HOLD_MS = 3_000L
    const val FLASH_MS = 3_000L
    const val DRAG_START_M = 0.02f

    /** I12: height (topM against the net height) and length (body length against the floor span along the body). */
    fun fit(p: Placed, room: RoomGeometry?): Fit {
        val s = p.species
        if (room == null) return Fit(null, null, 1)
        val net = room.netHeightM?.takeIf { it > 0.5f }
        val overHigh = net?.let { s.topM - it }?.takeIf { it > 0f }
        val span = floorSpan(room.floor, p.center, p.snoutDir)
        val overLong = span?.let { s.lengthM - it }?.takeIf { it > 0f }
        if (overHigh == null && overLong == null) return Fit(null, null, 1)
        var need = 1f
        if (net != null) need = max(need, s.topM / max(net - 0.2f, 0.3f))
        if (span != null) need = max(need, s.lengthM / max(span - 0.4f, 0.3f))
        val n = ceil(need).toInt().coerceIn(2, 10)
        return Fit(overHigh, overLong, n)
    }

    /**
     * Length of the floor along the line through [center] in direction [dir]. For a polygon that the line
     * crosses: the distance between the outermost crossings; otherwise the longest edge (design: "拿不到则用最长边").
     */
    fun floorSpan(floor: List<Vec3>, center: Vec3, dir: Vec3): Float? {
        if (floor.size < 3) return null
        val ts = ArrayList<Float>()
        for (i in floor.indices) {
            val a = floor[i]
            val b = floor[(i + 1) % floor.size]
            val t = crossLine(center, dir, a, b) ?: continue
            ts += t
        }
        if (ts.size >= 2) return ts.max() - ts.min()
        var longest = 0f
        for (i in floor.indices) longest = max(longest, hypot(floor[(i + 1) % floor.size].x - floor[i].x, floor[(i + 1) % floor.size].z - floor[i].z))
        return longest.takeIf { it > 0f }
    }

    /** Parameter t along center + t * dir (XZ) where it meets segment a-b, or null. */
    private fun crossLine(c: Vec3, d: Vec3, a: Vec3, b: Vec3): Float? {
        val ex = b.x - a.x
        val ez = b.z - a.z
        val den = d.x * ez - d.z * ex
        if (abs(den) < 1e-6f) return null
        val ax = a.x - c.x
        val az = a.z - c.z
        val t = (ax * ez - az * ex) / den
        val u = (ax * d.z - az * d.x) / den
        return if (u in 0f..1f) t else null
    }

    fun insideFloor(floor: List<Vec3>, p: Vec3): Boolean {
        if (floor.size < 3) return true
        var inside = false
        var j = floor.size - 1
        for (i in floor.indices) {
            val a = floor[i]
            val b = floor[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Where a tapped animal lands (I2): side-on to the viewer (snout to the viewer's right), pushed away to
     * at least half its width + 0.5 m, and moved along its length until it does not overlap another one.
     */
    fun place(species: Species, tap: Vec3, head: Vec3, floorY: Float, others: List<Placed>): Pair<Vec3, Float> {
        var flat = Vec3(tap.x - head.x, 0f, tap.z - head.z)
        if (flat.length < 1e-3f) flat = Vec3(0f, 0f, -1f)
        val view = flat.normalized()
        val snout = Vec3(-view.z, 0f, view.x)
        val yaw = yawOf(snout)
        var center = Vec3(tap.x, floorY, tap.z)
        val minDist = species.widthM / 2f + 0.5f
        val dist = Vec3(center.x - head.x, 0f, center.z - head.z).length
        if (dist < minDist) center = Vec3(head.x, floorY, head.z) + view * minDist
        if (others.isEmpty()) return center to yaw
        var step = 0
        var probe = center
        while (step < 200 && others.any { overlaps(species, probe, yaw, it) }) {
            step++
            val k = ((step + 1) / 2) * 0.1f * (if (step % 2 == 1) 1f else -1f)
            probe = center + snout * k
        }
        return probe to yaw
    }

    /** Footprint (oriented rectangle on the floor, true size) overlap test by separating axes. */
    fun overlaps(a: Species, ac: Vec3, ayaw: Float, b: Placed): Boolean {
        val ra = footprint(a, ac, ayaw, 1)
        val rb = footprint(b.species, b.center, b.yaw, b.scaleN)
        return rectsOverlap(ra, rb)
    }

    /** Four corners (XZ) of the floor footprint: from the snout back over the whole model, its width across. */
    fun footprint(s: Species, center: Vec3, yaw: Float, n: Int): List<Vec3> {
        val d = snoutDir(yaw)
        val side = Vec3(-d.z, 0f, d.x)
        val k = 1f / n
        val front = center + d * (s.lengthM / 2f * k)
        val back = front - d * (s.fullLengthM * k)
        val hw = s.widthM / 2f * k
        return listOf(front + side * hw, front - side * hw, back - side * hw, back + side * hw)
    }

    private fun rectsOverlap(a: List<Vec3>, b: List<Vec3>): Boolean {
        for (poly in listOf(a, b)) {
            for (i in 0 until 2) {
                val e = poly[i + 1] - poly[i]
                val axis = Vec3(-e.z, 0f, e.x)
                val (amin, amax) = project(a, axis)
                val (bmin, bmax) = project(b, axis)
                if (amax < bmin || bmax < amin) return false
            }
        }
        return true
    }

    private fun project(p: List<Vec3>, axis: Vec3): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in p) {
            val t = v.x * axis.x + v.z * axis.z
            lo = min(lo, t)
            hi = max(hi, t)
        }
        return lo to hi
    }

    /** I12 "装进房间": slide the animal along its length until both ends of the 1:n body are on the floor. */
    fun moveIntoFloor(p: Placed, n: Int, floor: List<Vec3>): Vec3 {
        if (floor.size < 3) return p.center
        val d = p.snoutDir
        val s = p.species
        val k = 1f / n
        fun ok(c: Vec3): Boolean {
            val front = c + d * (s.lengthM / 2f * k)
            val back = front - d * (s.fullLengthM * k)
            return insideFloor(floor, front) && insideFloor(floor, back)
        }
        if (ok(p.center)) return p.center
        for (i in 1..120) {
            val off = ((i + 1) / 2) * 0.05f * (if (i % 2 == 1) 1f else -1f)
            val c = p.center + d * off
            if (ok(c)) return c
        }
        return p.center
    }

    /** Where the "you" figure stands (design youSilhouette): 0.4 m beyond the snout, or the tail end if that is taken. */
    fun youSpot(near: Placed, others: List<Placed>): Vec3 {
        val s = near.species
        val d = near.snoutDir
        val k = 1f / near.scaleN
        val front = near.center + d * (s.lengthM / 2f * k + YOU_GAP_M)
        val blocked = others.any { it.uid != near.uid && insideRect(footprint(it.species, it.center, it.yaw, it.scaleN), front) }
        if (!blocked) return front
        val tail = near.center + d * (s.lengthM / 2f * k) - d * (s.fullLengthM * k + YOU_GAP_M)
        return tail
    }

    private fun insideRect(rect: List<Vec3>, p: Vec3): Boolean = insideFloor(rect, p)

    /** I11: the tallest head height above the floor in the last 10 s + 0.10 m, clamped to 1.00 - 2.10 m. */
    fun youHeight(maxHeadAboveFloor: Float?): Float = ((maxHeadAboveFloor ?: 1.58f) + 0.10f).coerceIn(1.0f, 2.1f)
}
