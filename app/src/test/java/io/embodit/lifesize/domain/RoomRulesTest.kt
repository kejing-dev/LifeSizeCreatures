package io.embodit.lifesize.domain

import io.embodit.lifesize.app.CreatureState
import io.embodit.lifesize.app.Flash
import io.embodit.lifesize.app.hint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomRulesTest {
    /** A 6 x 4 m floor centred on (0, -3), ceiling 2.6 m above it. */
    private val floor = listOf(Vec3(-3f, 0f, -5f), Vec3(3f, 0f, -5f), Vec3(3f, 0f, -1f), Vec3(-3f, 0f, -1f))
    private val room = RoomGeometry(floorY = 0f, ceilingY = 2.6f, floor = floor)

    @Before
    fun noMeasuredSizes() = ModelMetrics.sizes.clear()

    private fun placed(id: String, x: Float = 0f, z: Float = -3f, yaw: Float = 0f, n: Int = 1) = Placed(1, id, Vec3(x, 0f, z), yaw, n)

    @Test
    fun smallAnimalFits() {
        val fit = RoomRules.fit(placed("shiba"), room)
        assertFalse(fit.tooBig)
        assertEquals(1, fit.n)
    }

    @Test
    fun trexIsTallerAndLongerThanTheRoom() {
        ModelMetrics.sizes["trex"] = Vec3(12.3f, 5.17f, 3.17f)
        val fit = RoomRules.fit(placed("trex"), room)
        assertEquals(5.17f - 2.6f, fit.overHighM!!, 1e-3f)
        assertEquals(12.3f - 6f, fit.overLongM!!, 1e-3f)
        // n = ceil(max(5.17 / 2.4, 12.3 / 5.6)) = 3
        assertEquals(3, fit.n)
    }

    @Test
    fun noCeilingSkipsTheHeightCheck() {
        ModelMetrics.sizes["trex"] = Vec3(12.3f, 5.17f, 3.17f)
        val fit = RoomRules.fit(placed("trex"), room.copy(ceilingY = null))
        assertNull(fit.overHighM)
        assertNotNull(fit.overLongM)
    }

    @Test
    fun spanAlongTheBodyAxis() {
        assertEquals(6f, RoomRules.floorSpan(floor, Vec3(0f, 0f, -3f), snoutDir(0f))!!, 1e-4f)
        assertEquals(4f, RoomRules.floorSpan(floor, Vec3(0f, 0f, -3f), snoutDir((Math.PI / 2).toFloat()))!!, 1e-4f)
    }

    @Test
    fun placedSideOnAndPushedAway() {
        val horse = Roster["horse"]!!
        // tapped 0.3 m ahead: pushed out to half its width + 0.5 m, snout to the viewer's right
        val (center, yaw) = RoomRules.place(horse, Vec3(0f, 0f, -0.3f), Vec3(0f, 1.6f, 0f), 0f, emptyList())
        assertEquals(horse.widthM / 2f + 0.5f, -center.z, 1e-3f)
        val d = snoutDir(yaw)
        assertEquals(1f, d.x, 1e-4f)
        assertEquals(0f, d.z, 1e-4f)
    }

    @Test
    fun secondAnimalDoesNotOverlap() {
        val horse = Roster["horse"]!!
        val first = placed("horse", 0f, -3f)
        val (center, yaw) = RoomRules.place(horse, Vec3(0f, 0f, -3f), Vec3(0f, 1.6f, 0f), 0f, listOf(first))
        assertFalse(RoomRules.overlaps(horse, center, yaw, first))
    }

    @Test
    fun youStandBeyondTheSnout() {
        val p = placed("horse", 0f, -3f)
        val spot = RoomRules.youSpot(p, listOf(p))
        assertEquals(p.species.lengthM / 2f + RoomRules.YOU_GAP_M, spot.x, 1e-4f)
        assertEquals(-3f, spot.z, 1e-4f)
    }

    @Test
    fun youHeightIsClamped() {
        assertEquals(1.0f, RoomRules.youHeight(0.5f), 1e-4f)
        assertEquals(1.68f, RoomRules.youHeight(1.58f), 1e-4f)
        assertEquals(2.1f, RoomRules.youHeight(2.5f), 1e-4f)
    }

    @Test
    fun scaledAnimalMovesOntoTheFloor() {
        val p = placed("horse", 2.6f, -3f)
        val c = RoomRules.moveIntoFloor(p, 1, floor)
        val front = c + p.snoutDir * (p.species.lengthM / 2f)
        assertTrue(RoomRules.insideFloor(floor, front))
        assertTrue(abs(c.z + 3f) < 1e-4f)
    }

    @Test
    fun hintPriority() {
        val base = CreatureState(phase = Phase.ROOM, lang = Lang.ZH, room = room, animals = listOf(placed("shiba")))
        assertEquals(Copy(Lang.ZH).hintOne, base.hint().text)
        assertEquals(Copy(Lang.ZH).hintPlacing, base.copy(placing = "wolf").hint().text)
        assertEquals(Copy(Lang.ZH).hintLoading, base.copy(animals = listOf(placed("shiba").copy(loading = true)), placing = "wolf").hint().text)
        assertEquals(Copy(Lang.ZH).hintReplaced("灰狼"), base.copy(flash = Flash(Flash.Kind.REPLACED, "灰狼")).hint().text)
        assertEquals(Copy(Lang.EN).hintShared, CreatureState(lang = Lang.EN).hint().text)
        assertEquals(Copy(Lang.ZH).hintNoFloor, base.copy(phase = Phase.NO_FLOOR).hint().text)
    }
}
